package org.ergoplatform.appkit.ergotool

import org.ergoplatform.appkit.FileMockedErgoClient
import org.scalatest.{PropSpec, Matchers}
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import org.mockito.{MockitoSugar, ArgumentMatchersSugar}
import scalan.util.FileUtil
import org.ergoplatform.appkit.JavaHelpers._
import java.util.{List => JList}
import java.lang.{String => JString}
import java.nio.file.{Files, Paths}

import org.ergoplatform.settings.ErgoAlgos
import sigmastate.Values.ByteArrayConstant
import sigmastate.serialization.{ValueSerializer}
import org.ergoplatform.appkit.BlockchainContext
import org.ergoplatform.appkit.InputBox
import org.ergoplatform.appkit.Address
import org.ergoplatform.appkit.MockInputBox
import org.ergoplatform.appkit.ErgoToken
import org.ergoplatform.appkit.ErgoTreeTemplate
import org.ergoplatform.appkit.cli.{ConsoleTesting, Console, ConfigOption}

class ErgoToolSpec 
  extends PropSpec 
  with Matchers 
  with ScalaCheckDrivenPropertyChecks 
  with ConsoleTesting 
  with MockitoSugar 
  with ArgumentMatchersSugar {

  // test values which correspond to each other (see also addr.json storage file, which is obtained using this values)
  val addrStr = "3WzR39tWQ5cxxWWX6ys7wNdJKLijPeyaKgx72uqg9FJRBCdZPovL"
  val mnemonic = "slow silly start wash bundle suffer bulb ancient height spin express remind today effort helmet"
  val mnemonicPassword = ""
  val storagePassword = "def"
  val publicKey = "03f56b14197c1d0f9bf8418ed8c57a3179d12d9af98745fbd0ab3b9dd6883d24a8"
  val secretKey = "18258e98ea87256806275b71cb203dc234752488e01985d405426e5c6f4ea1d1"
  val masterKey = "18258e98ea87256806275b71cb203dc234752488e01985d405426e5c6f4ea1d1efe92e5adfcaa6f61173108305f7e3ba4ec9643a81dffa347879cf4d58d2a10006000200000000"

  val responsesDir = "src/test/resources/mockwebserver"
  def loadNodeResponse(name: String) = {
    FileUtil.read(FileUtil.file(s"$responsesDir/node_responses/$name"))
  }
  def loadExplorerResponse(name: String) = {
    FileUtil.read(FileUtil.file(s"$responsesDir/explorer_responses/$name"))
  }

  // NOTE, mainnet data is used for testing
  val testConfigFile = "ergo_tool_config.json"

  case class MockData(nodeResponses: Seq[String] = Nil, explorerResponses: Seq[String] = Nil)
  object MockData {
    def empty = MockData()
  }

  def runErgoTool(console: Console, name: String, args: Seq[String], data: MockData = MockData.empty) = {
    ErgoTool.run(name +: (Seq(ConfigOption.cmdText, testConfigFile) ++ args), console, {
      ctx => {
        val nrs = IndexedSeq(
          loadNodeResponse("response_NodeInfo.json"),
          loadNodeResponse("response_LastHeaders.json")) ++ data.nodeResponses
        val ers: IndexedSeq[String] = data.explorerResponses.toIndexedSeq
        new FileMockedErgoClient(nrs.convertTo[JList[JString]], ers.convertTo[JList[JString]])
      }
    })
  }

  /** Run the given command with expected console scenario (print and read operations)
   * @param name the command
   * @param args arguments of command line
   * @param expectedConsoleScenario input and output operations with the console (see parseScenario)
   */
  def runCommand(name: String, args: Seq[String], expectedConsoleScenario: String, data: MockData = MockData.empty): String = {
    val consoleOps = parseScenario(expectedConsoleScenario)
    runScenario(consoleOps) { console =>
      runErgoTool(console, name, args, data)
    }
  }

  def runCommandWithCtxStubber(name: String,
    args: Seq[String],
    expectedConsoleScenario: String,
    data: MockData = MockData.empty,
    ctxStubber: BlockchainContext => Unit): String = {
    val consoleOps = parseScenario(expectedConsoleScenario)
    runScenario(consoleOps) { console =>
      ErgoTool.run(name +: (Seq(ConfigOption.cmdText, testConfigFile) ++ args), console, {
        ctx => {
          val nrs = IndexedSeq(
            loadNodeResponse("response_NodeInfo.json"),
            loadNodeResponse("response_LastHeaders.json")) ++ data.nodeResponses
          val ers: IndexedSeq[String] = data.explorerResponses.toIndexedSeq
          new FileMockedErgoClientWithStubbedCtx(nrs.convertTo[JList[JString]], 
            ers.convertTo[JList[JString]],
            ctx => { val spiedCtx = spy(ctx); ctxStubber(spiedCtx); spiedCtx })
        }
      })
    }
  }

  def testCommand(name: String, args: Seq[String], expectedConsoleScenario: String, data: MockData = MockData.empty): Unit = {
    val consoleOps = parseScenario(expectedConsoleScenario)
    testScenario(consoleOps) { console =>
      runErgoTool(console, name, args, data)
    }
    ()
  }

  property("address command") {
    testCommand("address", Seq("testnet"),
      expectedConsoleScenario =
        s"""Mnemonic>::$mnemonic;
           |Mnemonic password>::$mnemonicPassword;
           |$addrStr::;
           |""".stripMargin)
  }

  property("mnemonic command") {
    val res = runCommand("mnemonic", Nil, "")
    res.split(" ").length shouldBe 15
  }

  property("checkAddress command") {
    testCommand("checkAddress", Seq("testnet", addrStr),
      expectedConsoleScenario =
        s"""Mnemonic>::$mnemonic;
           |Mnemonic password>::$mnemonicPassword;
           |Ok::;
           |""".stripMargin)
  }

  property("checkAddress command validates address format") {
    val res = runCommand("checkAddress", Seq("testnet", "someaddress"),
      expectedConsoleScenario =
        s"""Mnemonic>::$mnemonic;
          |Mnemonic password>::$mnemonicPassword;
          |""".stripMargin)
    res should include ("Invalid address encoding, expected base58 string: someaddress")
  }

  property("checkAddress command validates network type") {
    val res = runCommand("checkAddress",
      args = Seq("testnet", "9f4QF8AD1nQ3nJahQVkMj8hFSVVzVom77b52JU7EW71Zexg6N8v"),
      expectedConsoleScenario =
        s"""Mnemonic>::$mnemonic;
          |Mnemonic password> ::$mnemonicPassword;
          |""".stripMargin)
    res should include ("Network type of the address MAINNET don't match expected TESTNET")
  }

  property("listAddressBoxes command") {
    val data = MockData(
      Seq(
        loadNodeResponse("response_Box1.json"),
        loadNodeResponse("response_Box2.json"),
        loadNodeResponse("response_Box3.json"),
        loadNodeResponse("response_Box4.json")),
    Seq(
        loadExplorerResponse("response_boxesByAddressUnspent.json")))
    val res = runCommand("listAddressBoxes", Seq("9hHDQb26AjnJUXxcqriqY1mnhpLuUeC81C4pggtK7tupr92Ea1K"),
      expectedConsoleScenario = "", data)
    res should include ("d47f958b201dc7162f641f7eb055e9fa7a9cb65cc24d4447a10f86675fc58328")
    res should include ("e050a3af38241ce444c34eb25c0ab880674fc23a0e63632633ae14f547141c37")
    res should include ("26d6e08027e005270b38e5c5f4a73ffdb6d65a3289efb51ac37f98ad395d887c")
  }

  property("createStorage and extractStorage commands") {
    import ExtractStorageCmd._
    val storageDir = "storage"
    val storageFileName = "secret.json"
    val filePath = Paths.get(storageDir, storageFileName)
    try {
      // create a storage file
      testCommand("createStorage", Seq(storageDir, storageFileName),
        expectedConsoleScenario =
            s"""Enter Mnemonic Phrase>::$mnemonic;
              |Mnemonic password>::$mnemonicPassword;
              |Repeat Mnemonic password>::$mnemonicPassword;
              |Storage password>::$storagePassword;
              |Repeat Storage password>::$storagePassword;
              |Storage File: $filePath\n::;
              |""".stripMargin)

      // extract properties from the storage file
      Seq(
        PropAddress -> addrStr,
        PropPublicKey -> publicKey,
        PropMasterKey -> masterKey,
        PropSecretKey -> secretKey).foreach { case (propName, expectedValue) =>
        testCommand("extractStorage", Seq(filePath.toString, propName, "testnet"),
          expectedConsoleScenario =
            s"""Storage password>::$storagePassword;
              |$expectedValue\n::;
              |""".stripMargin)
        println(s"$propName: ok")
      }

      // try extract invalid property
      val res = runCommand("extractStorage", Seq(filePath.toString, "invalidProp", "testnet"),
        expectedConsoleScenario = s"ignored")
      res should include ("Please specify one of the supported properties")
    } finally {
      if (Files.exists(filePath)) Files.delete(filePath)
    }
  }

  property("send command") {
    val data = MockData(
      Seq(
        loadNodeResponse("response_Box1.json"),
        loadNodeResponse("response_Box2.json"),
        loadNodeResponse("response_Box3.json"),
        loadNodeResponse("response_Box4.json"),
        "21f84cf457802e66fb5930fb5d45fbe955933dc16a72089bf8980797f24e2fa1"),
      Seq(
        loadExplorerResponse("response_boxesByAddressUnspent.json")))
    val res = runCommand("send",
      args = Seq("storage/E2.json", "9f4QF8AD1nQ3nJahQVkMj8hFSVVzVom77b52JU7EW71Zexg6N8v", "1000000"),
      expectedConsoleScenario =
        s"""Storage password> ::abc;
          |""".stripMargin, data)
    println(res)
    res should include ("Server returned tx id: 21f84cf457802e66fb5930fb5d45fbe955933dc16a72089bf8980797f24e2fa1")
  }

  val sellOrderCmdArgs = Seq(
    "storage/E2.json",
    "50000000", // token price in NanoERGs
    "21f84cf457802e66fb5930fb5d45fbe955933dc16a72089bf8980797f24e2fa1", // tokenId
    "60", // token amount
    "5000000" // DEX fee
  )
  property("dex:SellOrder command") {
    val data = MockData(
      Seq(
        loadNodeResponse("response_Box1.json"),
        loadNodeResponse("response_Box2.json"),
        loadNodeResponse("response_Box3.json"),
        loadNodeResponse("response_Box4.json"),
        "21f84cf457802e66fb5930fb5d45fbe955933dc16a72089bf8980797f24e2fa1"),
      Seq(
        loadExplorerResponse("response_boxesByAddressUnspent.json")))
    val res = runCommand("dex:SellOrder",
      sellOrderCmdArgs,
      expectedConsoleScenario =
        s"""Storage password> ::abc;
           |""".stripMargin, data)
    println(res)
    res should include ("\"transactionId\": \"ded098c633a7bc145ba87dfa58ae9fde8be252d17aa36fbe734c7cb3f57bbaf3\",")
  }

  property("dex:SellOrder - failed, no assets") {
    val res = runCommandWithCtxStubber("dex:SellOrder",
      sellOrderCmdArgs,
      expectedConsoleScenario =
        s"""Storage password> ::abc;
          |""".stripMargin, 
      ctxStubber = { ctx: BlockchainContext =>
          val emptyInputBoxes = new java.util.ArrayList[InputBox](0)
          doReturn(emptyInputBoxes).when(ctx).getUnspentBoxesFor(any[Address])
      })
    res should include ("RuntimeException")
  }

  property("dex:SellOrder - failed, not enough tokens") {
    val token = new ErgoToken("21f84cf457802e66fb5930fb5d45fbe955933dc16a72089bf8980797f24e2fa1", 59L)
    val inputBoxes: IndexedSeq[InputBox] = IndexedSeq(MockInputBox(1000000000L, IndexedSeq(token)))
    val res = runCommandWithCtxStubber("dex:SellOrder",
      sellOrderCmdArgs,
      expectedConsoleScenario =
        s"""Storage password> ::abc;
          |""".stripMargin, 
      ctxStubber = { ctx: BlockchainContext =>
          doReturn(inputBoxes.convertTo[JList[InputBox]]).when(ctx).getUnspentBoxesFor(any[Address])
      })
    res should include ("RuntimeException")
  }

  property("dex:SellOrder - failed, not enough coins") {
    val token = new ErgoToken("21f84cf457802e66fb5930fb5d45fbe955933dc16a72089bf8980797f24e2fa1", 60L)
    val inputBoxes: IndexedSeq[InputBox] = IndexedSeq(MockInputBox(100L, IndexedSeq(token)))
    val res = runCommandWithCtxStubber("dex:SellOrder",
      args = sellOrderCmdArgs,
      expectedConsoleScenario =
        s"""Storage password> ::abc;
          |""".stripMargin, 
      ctxStubber = { ctx: BlockchainContext =>
          doReturn(inputBoxes.convertTo[JList[InputBox]]).when(ctx).getUnspentBoxesFor(any[Address])
      })
    res should include ("RuntimeException")
  }

  def sellOrderCheckUserInputValidation(tokenPrice: Long = 5000000L,
    tokenAmount: Long = 60L,
    dexFee: Long = 5000000L, 
    expectedStr: String) = {
    val args = Seq(
      "storage/E2.json",
      s"$tokenPrice",
      "21f84cf457802e66fb5930fb5d45fbe955933dc16a72089bf8980797f24e2fa1", // tokenId
      s"$tokenAmount",
      s"$dexFee",
    )
    val res = runCommand("dex:SellOrder",
      args,
      expectedConsoleScenario =
        s"""Storage password> ::abc;
           |""".stripMargin)
    println(res)
    res should include (expectedStr)
  }

  property("dex:SellOrder - failed, incorrect user input, tokenPrice") {
    sellOrderCheckUserInputValidation(tokenPrice = 0, 
      expectedStr = "java.lang.IllegalArgumentException: requirement failed: invalid tokenPrice")
  }

  property("dex:SellOrder - failed, incorrect user input, token amount") {
    sellOrderCheckUserInputValidation(tokenAmount = 0, 
      expectedStr = "java.lang.IllegalArgumentException: requirement failed: invalid token amount")
  }

  property("dex:SellOrder - failed, incorrect user input, dex fee") {
    sellOrderCheckUserInputValidation(dexFee = 0, 
      expectedStr = "java.lang.IllegalArgumentException: requirement failed: invalid DEX fee")
  }

  val buyOrderCmdArgs = Seq(
    "storage/E2.json",
    "50000000", // token price in NanoERGs
    "21f84cf457802e66fb5930fb5d45fbe955933dc16a72089bf8980797f24e2fa1", // tokenId
    "60", // token amount
    "5000000", // DEX fee
  )

  property("dex:BuyOrder command") {
    val data = MockData(
      Seq(
        loadNodeResponse("response_Box1.json"),
        loadNodeResponse("response_Box2.json"),
        loadNodeResponse("response_Box3.json"),
        loadNodeResponse("response_Box4.json"),
        "21f84cf457802e66fb5930fb5d45fbe955933dc16a72089bf8980797f24e2fa1"),
      Seq(
        loadExplorerResponse("response_boxesByAddressUnspent.json")))
    val res = runCommand("dex:BuyOrder",
      args = buyOrderCmdArgs,
      expectedConsoleScenario =
        s"""Storage password> ::abc;
           |""".stripMargin, data)
    println(res)
    res should include ("\"transactionId\": \"1bf5cdad177b24f9543573337ca789982b4b6bd9ac2be8f6e6113b14e52bd544\",")
  }

    property("dex:BuyOrder - failed, no coins") {
    val res = runCommandWithCtxStubber("dex:BuyOrder",
      buyOrderCmdArgs,
      expectedConsoleScenario =
        s"""Storage password> ::abc;
          |""".stripMargin, 
      ctxStubber = { ctx: BlockchainContext =>
          val emptyInputBoxes = new java.util.ArrayList[InputBox](0)
          doReturn(emptyInputBoxes).when(ctx).getUnspentBoxesFor(any[Address])
      })
    res should include ("RuntimeException")
  }

  property("dex:BuyOrder - failed, not enough coins") {
    val inputBoxes: IndexedSeq[InputBox] = IndexedSeq(MockInputBox(100L))
    val res = runCommandWithCtxStubber("dex:BuyOrder",
      args = buyOrderCmdArgs,
      expectedConsoleScenario =
        s"""Storage password> ::abc;
          |""".stripMargin, 
      ctxStubber = { ctx: BlockchainContext =>
          doReturn(inputBoxes.convertTo[JList[InputBox]]).when(ctx).getUnspentBoxesFor(any[Address])
      })
    res should include ("RuntimeException")
  }

  def buyOrderCheckUserInputValidation(ergAmount: Long = 5000000L,
    tokenAmount: Long = 60L,
    dexFee: Long = 5000000L, 
    expectedStr: String) = {
    val args = Seq(
      "storage/E2.json",
      s"$ergAmount", // token price in NanoERGs
      "21f84cf457802e66fb5930fb5d45fbe955933dc16a72089bf8980797f24e2fa1", // tokenId
      s"$tokenAmount", // token amount
      s"$dexFee", // DEX fee
    )
    val res = runCommand("dex:BuyOrder",
      args,
      expectedConsoleScenario =
        s"""Storage password> ::abc;
           |""".stripMargin)
    println(res)
    res should include (expectedStr)
  }

  property("dex:BuyOrder - failed, incorrect user input, ergAmount") {
    buyOrderCheckUserInputValidation(ergAmount = 0,
      expectedStr = "java.lang.IllegalArgumentException: requirement failed: invalid ergAmount")
  }

  property("dex:BuyOrder - failed, incorrect user input, token amount") {
    buyOrderCheckUserInputValidation(tokenAmount = 0,
      expectedStr = "java.lang.IllegalArgumentException: requirement failed: invalid token amount")
  }

  property("dex:BuyOrder - failed, incorrect user input, DEX fee") {
    buyOrderCheckUserInputValidation(dexFee = 0,
      expectedStr = "java.lang.IllegalArgumentException: requirement failed: invalid DEX fee")
  }

  property("dex:MatchOrders command") {
    val data = MockData(
      Seq(
        loadNodeResponse("response_Box_AAE_seller_contract.json"),
        loadNodeResponse("response_Box_AAE_buyer_contract.json"),
        "21f84cf457802e66fb5930fb5d45fbe955933dc16a72089bf8980797f24e2fa1"),
      Seq(
      ))
    val res = runCommand("dex:MatchOrders",
      args = Seq(
        "storage/E2.json",
        "655ad79f579677fa0f44e72713ecd8f054e534a02e66d8aef4fc2729b9e62b76", // seller contract box id
        "969482db6643a16b6d8f4c8b50d0a9d5b47a698014c927ee0fa495e2adabbb8e", // buyer contract box id
        "7000000" // minimum DEX fee
      ),
      expectedConsoleScenario =
        s"""Storage password> ::abc;
           |""".stripMargin, data)
    println(res)
    res should include ("\"transactionId\": \"2e68ceeac25b5e0d7ddc5f3180285a7a89e769907beb852e105818e1aba38487\",")
  }

  property("dex:ListMatchingOrders command") {
    val data = MockData(
      Seq(
        loadNodeResponse("response_Box_AAE_seller_contract.json"),
        loadNodeResponse("response_Box1.json"),
        loadNodeResponse("response_Box2.json"),
        loadNodeResponse("response_Box3.json"),
        loadNodeResponse("response_Box_AAE_buyer_contract.json"),
        loadNodeResponse("response_Box1.json"),
        loadNodeResponse("response_Box2.json"),
        loadNodeResponse("response_Box3.json"),
        ),
      Seq(
        loadExplorerResponse("response_boxesByAddressUnspent.json"),
        loadExplorerResponse("response_boxesByAddressUnspent.json")
      )
    )

    val res = runCommand("dex:ListMatchingOrders",
      args = Seq(
      ),
      expectedConsoleScenario = "",
      data)
    println(res)
    res should include ("969482db6643a16b6d8f4c8b50d0a9d5b47a698014c927ee0fa495e2adabbb8e, 9000000")
  }

  property("dex:IssueToken command") {
    def encodedRegValue(str: String): String = {
      ErgoAlgos.encode(ValueSerializer.serialize(ByteArrayConstant(str.getBytes)))
    }

    val data = MockData(
      Seq(
        loadNodeResponse("response_Box1.json"),
        loadNodeResponse("response_Box2.json"),
        loadNodeResponse("response_Box3.json"),
        loadNodeResponse("response_Box4.json"),
        "21f84cf457802e66fb5930fb5d45fbe955933dc16a72089bf8980797f24e2fa1"),
      Seq(
        loadExplorerResponse("response_boxesByAddressUnspent.json")))
    val ergAmount = "50000000" // NanoERGs
    val tokenAmount = "1000000"
    val tokenName = "TKN"
    val tokenDesc = "Generic token"
    val numberOfDecimals = "2"
    val res = runCommand("dex:IssueToken",
      args = Seq(
        "storage/E2.json",
        ergAmount,
        tokenAmount,
        tokenName,
        tokenDesc,
        numberOfDecimals,
      ),
      expectedConsoleScenario =
        s"""Storage password> ::abc;
           |""".stripMargin, data)
    println(res)
    res should include
    s"""
          "amount": $tokenAmount
        }
      ],
      "additionalRegisters": {
        "R4": "${encodedRegValue(tokenName)}",
        "R5": "${encodedRegValue(tokenDesc)}",
        "R6": "${encodedRegValue(numberOfDecimals)}"
      },
    """.stripMargin

    res should include
    s"""
       | "value": $ergAmount,
       |""".stripMargin
  }

  property("dex:CancelOrder command for sell order") {
    val data = MockData(
      Seq(
        loadNodeResponse("response_Box_AAE_seller_contract.json"),
        "21f84cf457802e66fb5930fb5d45fbe955933dc16a72089bf8980797f24e2fa1"),
      Seq(
      ))
    val res = runCommand("dex:CancelOrder",
      args = Seq(
        "storage/E2.json",
        "655ad79f579677fa0f44e72713ecd8f054e534a02e66d8aef4fc2729b9e62b76", // seller contract box id
      ),
      expectedConsoleScenario =
        s"""Storage password> ::abc;
           |""".stripMargin, data)
    println(res)
    res should include ("\"transactionId\": \"f719f7e4c5600491c729f5cf35d13d873057624d26dfed15f48fcad8fdde54a7\",")
  }

  property("dex:CancelOrder command for buy order") {
    val data = MockData(
      Seq(
        loadNodeResponse("response_Box_AAE_buyer_contract.json"),
        "21f84cf457802e66fb5930fb5d45fbe955933dc16a72089bf8980797f24e2fa1"),
      Seq(
      ))
    val res = runCommand("dex:CancelOrder",
      args = Seq(
        "storage/E2.json",
        "969482db6643a16b6d8f4c8b50d0a9d5b47a698014c927ee0fa495e2adabbb8e", // buyer contract box id
      ),
      expectedConsoleScenario =
        s"""Storage password> ::abc;
           |""".stripMargin, data)
    println(res)
    res should include ("\"transactionId\": \"3acd12f5a85cfeba4096d2d5a562ac3f44ee2bb7e3ba4861b1d85d2f01eac048\",")
  }

  property("dex:ListMyOrders command") {
    val data = MockData(
      Seq(
        loadNodeResponse("response_Box_AAE_seller_contract.json"),
        loadNodeResponse("response_Box1.json"),
        loadNodeResponse("response_Box2.json"),
        loadNodeResponse("response_Box3.json"),
        loadNodeResponse("response_Box_AAE_buyer_contract.json"),
        loadNodeResponse("response_Box1.json"),
        loadNodeResponse("response_Box2.json"),
        loadNodeResponse("response_Box3.json"),
      ),
      Seq(
        loadExplorerResponse("response_boxesByAddressUnspent.json"),
        loadExplorerResponse("response_boxesByAddressUnspent.json")
      )
    )

    val res = runCommand("dex:ListMyOrders",
      args = Seq(
        "storage/E2.json",
      ),
      expectedConsoleScenario =
        s"""Storage password> ::abc;
           |""".stripMargin, data)
    println(res)
    res should include ("655ad79f579677fa0f44e72713ecd8f054e534a02e66d8aef4fc2729b9e62b76 21f84cf457802e66fb5930fb5d45fbe955933dc16a72089bf8980797f24e2fa1 60            50000000     5000000")
    res should include ("969482db6643a16b6d8f4c8b50d0a9d5b47a698014c927ee0fa495e2adabbb8e 21f84cf457802e66fb5930fb5d45fbe955933dc16a72089bf8980797f24e2fa1 60            55000000")
  }

  property("dex:ListMyOrders command: no orders") {
    val data = MockData(
      Seq(
        loadNodeResponse("response_Box_AAE_seller_contract.json"),
        loadNodeResponse("response_Box1.json"),
        loadNodeResponse("response_Box2.json"),
        loadNodeResponse("response_Box3.json"),
        loadNodeResponse("response_Box_AAE_buyer_contract.json"),
        loadNodeResponse("response_Box1.json"),
        loadNodeResponse("response_Box2.json"),
        loadNodeResponse("response_Box3.json"),
      ),
      Seq(
        loadExplorerResponse("response_boxesByAddressUnspent.json"),
        loadExplorerResponse("response_boxesByAddressUnspent.json")
      )
    )
    val inputBoxes = IndexedSeq[InputBox]()
    val res = runCommandWithCtxStubber("dex:ListMyOrders",
      args = Seq(
        "storage/E2.json",
      ),
      expectedConsoleScenario =
        s"""Storage password> ::abc;
           |""".stripMargin, 
      ctxStubber = { ctx: BlockchainContext =>
          doReturn(inputBoxes.convertTo[JList[InputBox]])
            .when(ctx)
            .getUnspentBoxesForErgoTreeTemplate(any[ErgoTreeTemplate])
      })
    println(res)
    res should not include ("655ad79f579677fa0f44e72713ecd8f054e534a02e66d8aef4fc2729b9e62b76 21f84cf457802e66fb5930fb5d45fbe955933dc16a72089bf8980797f24e2fa1 60            50000000     5000000")
    res should not include ("969482db6643a16b6d8f4c8b50d0a9d5b47a698014c927ee0fa495e2adabbb8e 21f84cf457802e66fb5930fb5d45fbe955933dc16a72089bf8980797f24e2fa1 60            55000000")
  }

  property("dex:ShowOrderBook command") {
    val data = MockData(
      Seq(
        loadNodeResponse("response_Box_AAE_seller_contract.json"),
        loadNodeResponse("response_Box1.json"),
        loadNodeResponse("response_Box2.json"),
        loadNodeResponse("response_Box3.json"),
        loadNodeResponse("response_Box_AAE_buyer_contract.json"),
        loadNodeResponse("response_Box1.json"),
        loadNodeResponse("response_Box2.json"),
        loadNodeResponse("response_Box3.json"),
        ),
      Seq(
        loadExplorerResponse("response_boxesByAddressUnspent.json"),
        loadExplorerResponse("response_boxesByAddressUnspent.json")
      )
    )

    val res = runCommand("dex:ShowOrderBook",
      args = Seq(
        "21f84cf457802e66fb5930fb5d45fbe955933dc16a72089bf8980797f24e2fa1"
      ),
      expectedConsoleScenario = "",
      data)
    println(res)
    res should include ("  Amount   Total(including DEX fee)")
    res should include ("      60   55000000")
  }

}

