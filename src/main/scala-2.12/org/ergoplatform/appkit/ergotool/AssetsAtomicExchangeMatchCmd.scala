package org.ergoplatform.appkit.ergotool

import java.io.File
import java.util

import org.ergoplatform.appkit.Parameters.MinFee
import org.ergoplatform.appkit._
import org.ergoplatform.appkit.config.ErgoToolConfig
import org.ergoplatform.appkit.console.Console
import org.ergoplatform.appkit.impl.{ErgoTreeContract, ScalaBridge}
import sigmastate.eval.CSigmaProp
import sigmastate.verification.contract.AssetsAtomicExchangeCompilation
import special.sigma.SigmaProp

/** Creates and sends a new transaction with boxes that match given buyer and seller contracts for AssetsAtomicExchange
  *
  * Steps:<br/>
  * 1) request storage password from the user<br/>
  * 2) read storage file, unlock using password and get secret<br/>
  * 3) get master public key and compute sender's address<br/>
  * 4) find the box with buyer's contract (by buyerHolderBoxId)<br/>
  * 5) find the box with seller's contract (by sellerHolderBoxId)<br/>
  * 6) select sender's coins to cover the transaction fee, and computes the amount of change<br/>
  * 7) create output box for buyer's tokens<br/>
  * 8) create output box for seller's Ergs<br/>
  * 9) create a transaction using buyer's and seller's contract boxes (from steps 4,5) as inputs<br/>
  * 10) sign (using secret key) the transaction<br/>
  * 11) if no `--dry-run` option is specified, send the transaction to the network<br/>
  *    otherwise skip sending<br/>
  * 12) serialize transaction to Json and print to the console<br/>
  *
  * @param storageFile storage with secret key of the sender
  * @param storagePass password to access sender secret key in the storage
  * @param sellerHolderBoxId BoxId of the seller's contract
  * @param buyerHolderBoxId BoxId of the buyer's contract
  * @param buyerAddress address to receive tokens
  * @param sellerAddress address to receive Ergs
  */
case class AssetsAtomicExchangeMatchCmd(toolConf: ErgoToolConfig,
                                        name: String,
                                        storageFile: File,
                                        storagePass: Array[Char],
                                        sellerHolderBoxId: ErgoId,
                                        buyerHolderBoxId: ErgoId,
                                        sellerAddress: Address,
                                        buyerAddress: Address) extends Cmd with RunWithErgoClient {

  def loggedStep[T](msg: String, console: Console)(step: => T): T = {
    console.print(msg + "...")
    val res = step
    val status = if (res != null) "Ok" else "Error"
    console.println(s" $status")
    res
  }

  override def runWithClient(ergoClient: ErgoClient, runCtx: AppContext): Unit = {
    val console = runCtx.console
    ergoClient.execute(ctx => {
      val senderProver = loggedStep("Creating prover", console) {
        BoxOperations.createProver(ctx, storageFile.getPath, String.valueOf(storagePass))
      }
      val buyerHolderBox = loggedStep(s"Loading buyer's box (${buyerHolderBoxId.toString})", console) {
        ctx.getBoxesById(buyerHolderBoxId.toString).head
      }
      val sellerHolderBox = loggedStep(s"Loading seller's box (${sellerHolderBoxId.toString})", console) {
        ctx.getBoxesById(sellerHolderBoxId.toString).head
      }
      val sender = senderProver.getAddress
      val unspent = loggedStep(s"Loading unspent boxes from at address $sender", console) {
        ctx.getUnspentBoxesFor(sender)
      }
      val boxesForTxFee = BoxOperations.selectTop(unspent, MinFee)
      boxesForTxFee.addAll(util.Arrays.asList(buyerHolderBox, sellerHolderBox))
      val inputBoxes = boxesForTxFee
      val txB = ctx.newTxBuilder
      val buyerTokensOutBox = txB.outBoxBuilder
        .value(sellerHolderBox.getValue)
        // TODO: check buyerAddress is in the buyer's contract ErgoTree (to avoid typos)
        .contract(ctx.compileContract(
          ConstantsBuilder.create
            .item("recipientPk", buyerAddress.getPublicKey)
            .build(),
          "{ recipientPk }"))
        // TODO: check token id and amount in the buyer's contract ErgoTree
        .tokens(sellerHolderBox.getTokens.get(0))
        .registers(ErgoValue.of(buyerHolderBoxId.getBytes))
        .build()
      val sellerErgsOutBox = txB.outBoxBuilder
        .value(buyerHolderBox.getValue)
        // TODO: check sellerAddress is in the seller's contract ErgoTree (to avoid typos)
        .contract(ctx.compileContract(
          ConstantsBuilder.create
            .item("recipientPk", sellerAddress.getPublicKey)
            .build(),
          "{ recipientPk }"))
        .registers(ErgoValue.of(sellerHolderBoxId.getBytes))
        .build()
      val tx = txB
        .boxesToSpend(inputBoxes).outputs(buyerTokensOutBox, sellerErgsOutBox)
        .fee(MinFee)
        .sendChangeTo(senderProver.getP2PKAddress)
        .build()
      val signed = loggedStep(s"Signing the transaction", console) {
        senderProver.sign(tx)
      }
      val txJson = signed.toJson(true)
      console.println(s"Tx: $txJson")

      if (!runCtx.isDryRun) {
        val txId = loggedStep(s"Sending the transaction", console) {
          ctx.sendTransaction(signed)
        }
        console.println(s"Server returned tx id: $txId")
      }
    })
  }
}

object AssetsAtomicExchangeMatchCmd extends CmdDescriptor(
  name = "AssetAtomicExchangeMatch", cmdParamSyntax = "<wallet file> <sellerHolderBoxId> <buyerHolderBoxId>    <sellerAddress> <buyerAddress>",
  description = "match an existing token seller's contract (by <sellerHolderBoxId>) and an existing buyer's contract (by <buyerHolderBoxId) and send tokens to <buyerAddress> and Ergs to <sellerAddress> with the given <wallet file> to sign transaction (requests storage password)") {

  override def parseCmd(ctx: AppContext): Cmd = {
    val args = ctx.cmdArgs
    val storageFile = new File(if (args.length > 1) args(1) else error("Wallet storage file path is not specified"))
    if (!storageFile.exists()) error(s"Specified wallet file is not found: $storageFile")
    val sellerHolderBoxId = ErgoId.create(if (args.length > 2) args(2) else error("seller contract box id is not specified"))
    val buyerHolderBoxId = ErgoId.create(if (args.length > 3) args(3) else error("buyer contract box id is not specified"))
    val sellerAddress = Address.create(if (args.length > 4) args(4) else error("seller address is not specified"))
    val buyerAddress = Address.create(if (args.length > 5) args(5) else error("buyer address is not specified"))
    val pass = ctx.console.readPassword("Storage password>")
    AssetsAtomicExchangeMatchCmd(ctx.toolConf, name, storageFile, pass, sellerHolderBoxId, buyerHolderBoxId, sellerAddress, buyerAddress)
  }
}
