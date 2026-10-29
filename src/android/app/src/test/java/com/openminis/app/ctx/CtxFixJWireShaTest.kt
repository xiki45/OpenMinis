package com.openminis.app.ctx

import com.openminis.app.imagerepro.ImageRealFixtures
import com.openminis.app.imagerepro.ImageRealJvmCodec
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Task Android-J: byte-level proof that the two fixes change only the requests
 * they are meant to change.
 *
 * Every C01–C32 scenario (live, and restart where it has one) plus the P-a5
 * request is built through RequestContextBuilder and serialized by all four
 * providers; each raw body is hashed (sha256, the session's temp root
 * normalized to `<ROOT>`). [BEFORE] holds the hashes captured on the code
 * BEFORE the fixes (HEAD 5d912c58f). After the fixes every hash must be
 * identical except the ones listed in [CHANGED_BY_FIX], each with its reason.
 * One test per scenario, so a run holds one scenario's bodies at a time.
 */
@RunWith(Parameterized::class)
class CtxFixJWireShaTest(private val id: String) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun ids(): List<Array<Any>> = (CtxScenarios.all.map { it.id } + "P-a5").map { arrayOf<Any>(it) }

        private val BEFORE: Map<String, String> = mapOf(
            "C01.live.anthropic" to "62a14c0ad8cbf414ea3a2c82d87dc58b739b22b59d1eed77c6f661fe593f2aad",
            "C01.live.openai.chat" to "de067e4ad36224c9e4e2101bbe0188f0837d1c8fe556e5f8dadf5487fdf77a99",
            "C01.live.openai.responses" to "b577325197c7dd72420d78af64675acaa567c555885b23a06791f960dea8e688",
            "C01.live.gemini" to "5bce3f3413a3a5b55083332914bc2615ced2f54bec25d2ddd1c094545615f5d5",
            "C01.restart.anthropic" to "62a14c0ad8cbf414ea3a2c82d87dc58b739b22b59d1eed77c6f661fe593f2aad",
            "C01.restart.openai.chat" to "de067e4ad36224c9e4e2101bbe0188f0837d1c8fe556e5f8dadf5487fdf77a99",
            "C01.restart.openai.responses" to "b577325197c7dd72420d78af64675acaa567c555885b23a06791f960dea8e688",
            "C01.restart.gemini" to "5bce3f3413a3a5b55083332914bc2615ced2f54bec25d2ddd1c094545615f5d5",
            "C02.live.anthropic" to "30f5e4637cfd681a12354e11ead8c474fcd5ae9fa9e8d5ede2758938c26bcb54",
            "C02.live.openai.chat" to "24afb603c150da97d578caa0ee4aa217e2a5aabdd64cf7cbdd068f8f864a34e9",
            "C02.live.openai.responses" to "7c32bf94d23e105bd40ca48c2838f52663439b753f98c1e7494c7a4c658d45e6",
            "C02.live.gemini" to "1a22acb54d8f30baa9a31d8d530b152157fcf5e0368f6a29d659782de4d44e64",
            "C02.restart.anthropic" to "30f5e4637cfd681a12354e11ead8c474fcd5ae9fa9e8d5ede2758938c26bcb54",
            "C02.restart.openai.chat" to "24afb603c150da97d578caa0ee4aa217e2a5aabdd64cf7cbdd068f8f864a34e9",
            "C02.restart.openai.responses" to "7c32bf94d23e105bd40ca48c2838f52663439b753f98c1e7494c7a4c658d45e6",
            "C02.restart.gemini" to "1a22acb54d8f30baa9a31d8d530b152157fcf5e0368f6a29d659782de4d44e64",
            "C03.live.anthropic" to "70be480f3e3be9893b9ee9b1af6eb4a8e60e2dab0a0bed80de5908c46c190fbb",
            "C03.live.openai.chat" to "5bd962d830b329be41145ad4128bfb2fd96ef5081b5718dd75ed5bfa0c4fbf69",
            "C03.live.openai.responses" to "5622a4086b6eef1165a7c2b7af4f9d1d37b2278298fcc367d3f703093b4759e8",
            "C03.live.gemini" to "6dbdde9675015372e1bc257575a92db2480f64c773640e818651fed63475a3e4",
            "C03.restart.anthropic" to "5621040792d8f798a37b915f13bdc2d74339a9420b9990dc66e7f8d1bf738142",
            "C03.restart.openai.chat" to "418614e07f647de62774353af4620c6b39c3941cda70dec8e8db8fe47ef4ba3c",
            "C03.restart.openai.responses" to "ec1750451f18746207d06c9bbee940437712134524b5880742306faa8cbe46a1",
            "C03.restart.gemini" to "fbcfab58587ddb4bfd63cdc64e91a435c95f404f2d165203fb226300f03106b5",
            "C04.live.anthropic" to "b9162cd6f40dbe067cea72924e21ba253ae078c0206417cb08548e5a0ee62508",
            "C04.live.openai.chat" to "c06629c99b94469b86f6753b4cb5a73b202084b8bac3c804833b3cbaded7965d",
            "C04.live.openai.responses" to "b9a119662c060561a1d1ae0a55edfea7b1ffc1e4934e3f3f0d8936c5add5a001",
            "C04.live.gemini" to "ba09a1414dd7f2b641822364bb273fcb17e8e46d7454ac6f20c365e3cb4c2283",
            "C04.restart.anthropic" to "7719d7d2bb5236737075c54e676bcbfef57d236b3ba93d35a40f8cb3ce48e99d",
            "C04.restart.openai.chat" to "10123e791e181f79e370238c6a24889598627cada4eadb9b9a699d64a1e66f59",
            "C04.restart.openai.responses" to "302d851679db290244afe1eb17216ac90aa309e9299f03e09a173249b3864028",
            "C04.restart.gemini" to "9d79c7f9edf6ff29361af0b363f6cbfa3629cf107014d1f8f576b9dd27018c36",
            "C05.live.anthropic" to "d4756d2443d55a4c247575c93d486decd11e24d3aafb4884c12ba9e54a18a95f",
            "C05.live.openai.chat" to "69524fcd0d1d340e8a1663178a70ce03201538ddf021d93827646cfd7244deed",
            "C05.live.openai.responses" to "428df269d77268066b7d01824e9e5d94732321b8a384e492be9955fb5a2b444d",
            "C05.live.gemini" to "bc97fff3a6088d89e9f757e3552cad672e8e8f4f0ae453974e006b9cd0b2b2c9",
            "C05.restart.anthropic" to "5ad802c7b2ad79bd513d105d3bf8fb0b815a2a8b83a9f50a7fbdd8a150c6602a",
            "C05.restart.openai.chat" to "2d6213a515dd3b30442767f995999e5d2b3f20e1b128670f5c500ac53450941b",
            "C05.restart.openai.responses" to "e71ffe3bc81b75a29bff5597e97185c7fad5723b1edeb5ef43dff0c3e9858acd",
            "C05.restart.gemini" to "65e9791337e5dc026fcb4c7df8a183ba6747ebb116486f3065e185f7529f35f2",
            "C06.live.anthropic" to "23fde5277baae35f33a51e5e5a54c61ccbd843204674f4338661cf29e1e96ad4",
            "C06.live.openai.chat" to "6b1f872f06808008238680a04c7783b6668a1312a58d005d668cb3edd199fe59",
            "C06.live.openai.responses" to "f87b6318c2a7218d8df331f9ed8c1c18f10f972c58eb313d9945ca6d22c4357c",
            "C06.live.gemini" to "a090b12cadede8294ddf8af8e23ccb6edfd75a068370790ef9d8bdd0d3c134af",
            "C06.restart.anthropic" to "8398b21e2990723124369984f01d16bde01cce1bb12b644343a7b21eb16a7360",
            "C06.restart.openai.chat" to "16144572b029f99f5a6effec10fe5d868a3244b3d7227eaa8db9ba9bf29657ef",
            "C06.restart.openai.responses" to "d55f512890e239bde668925f4bd5bf849876e7427960dcbf227fcd4a6c0a2a37",
            "C06.restart.gemini" to "885b2e50a317cd4bdf4096125c688e35ad117233f2c647efb2dd49a18094dc53",
            "C07.live.anthropic" to "51a847f68f491dd692ee00bdfef4a4ea21eeff63bb55b79e3056bae93f13aaf0",
            "C07.live.openai.chat" to "147b3e218d819441129f713d048b3169567dffdfcfca8a05d20a36370a07478c",
            "C07.live.openai.responses" to "04eefe0e12edc0de2a04f2a2e3fb2c1883028bf3eb863c64128311f5622e5011",
            "C07.live.gemini" to "4379f4277298daff17f710ea8f9138adf08256a2060010f070912232710c2341",
            "C07.restart.anthropic" to "f2e3d6b194f8bf78c87437f32d1fc38503a710fc5c7075d1470c3a715dae2af4",
            "C07.restart.openai.chat" to "4fe3f18bf0652b52b505ac33df12ff746d299c6a6af2a5f021db3a4c7242a038",
            "C07.restart.openai.responses" to "a1124b00adee0153b4837f1c82fb44e7325de1c3e3d72c8b5f1a8524f142bf1b",
            "C07.restart.gemini" to "f9d14cbe4c3b6a46b01c0154a48468d4fe7d68a7a674a000cf0c8268f8d60cf2",
            "C08.live.anthropic" to "008dbae60663485213d6cadf80601b0b8b4fd7cd74b2ec9fb5fe2580e5e2c0af",
            "C08.live.openai.chat" to "d158835c2c8a7021f2f48e32e84a6a1583c392946f36a5ffcba06fc4010cb522",
            "C08.live.openai.responses" to "157eb9320aa94c16364a2f58b39ed107f28b731d4bad4c6d096e597ff0fe0cd9",
            "C08.live.gemini" to "28ecc05b27e6245d4d96886979b6a9300265d723467bb73929ffffac6b55e32a",
            "C08.restart.anthropic" to "706bc92786961947f0d39b034ff634769f5f41cf46ad6fc1ce75cc1cc250aaf4",
            "C08.restart.openai.chat" to "ae05bba252feee9670b1ac63792277d8ce3dcbbe0777a33b3733409195aaf3f2",
            "C08.restart.openai.responses" to "83162bcb03b90f22e5c496ab02f520ac8767be451f08b1769938cdb5d2a065a2",
            "C08.restart.gemini" to "de3505306daba386aa84abb0578c3919db5ee312b3a3d0f79733b86a22ba2ae5",
            "C09.live.anthropic" to "f13c0bb3ba397f2db0bf42ed3a00a68cb863c77543d08832417f9690b3dc9b44",
            "C09.live.openai.chat" to "a3f1f93d51ddda334b32bca380b199b77b8c4f6e98c85c4e5d750dddfd40a537",
            "C09.live.openai.responses" to "bf43036264953c5cde9bdec29f3691d7ff89bb540906e62151152cc4bf9b40e3",
            "C09.live.gemini" to "40e5634acce34f520092647303e652127d6320d0a0deffef17d34721fb12b54b",
            "C09.restart.anthropic" to "4ba85b51f32752dad8f9923de7ef55ca1f059fdf2e3a720236bd69cf7e2f0a68",
            "C09.restart.openai.chat" to "61a0fe70ee5e7a1332b78e19e67fe1e776f37325cb3b2cb87dfdc0d0f94aec67",
            "C09.restart.openai.responses" to "3f9cfc623adf38ed9f29de6dd7f8f353ce21cb453ef74779fcbb6c90fc7ba861",
            "C09.restart.gemini" to "d7834da642570e813a5f2fd47b6c7df2b411a556efc4d117ac70fed90ea74ecf",
            "C10.live.anthropic" to "d4929f413309f6f85dccbbe0cf42290bad488b13d5e54edf92b45a9d371bd925",
            "C10.live.openai.chat" to "7e7d299afd57eeebc20f123824c2db6f53dbbd4d6488559708176f8af747d0db",
            "C10.live.openai.responses" to "8df21812105bab62ab936e37995ab9ded966cfea7c334d08108dff2c507d675f",
            "C10.live.gemini" to "f877b2e0a76df91fd7fd944f1e8d0519591aa93a819df0a809c8f6a0ffdd6c0d",
            "C10.restart.anthropic" to "62174d11c2cf78b80aeaf7c89c7bedf2de4133e8dc9526df3d103320eedcef6d",
            "C10.restart.openai.chat" to "d7cb569b4443744f0135ca8b208fc0fe0a748b72e2871309f8cf62f8eb78e938",
            "C10.restart.openai.responses" to "058de7e250577f129ef08908e12762040d2aa5bf2f895aaa6d0dbec7274444af",
            "C10.restart.gemini" to "ecfcdb449e3d2f4b5690699a4c80bbdb427ff01a90a7715571a9a988a3a5abfe",
            "C11.live.anthropic" to "3fb9f989a98a74dfd35f40d98c176a6d96f09cf5899f49fbcc004b69127fb363",
            "C11.live.openai.chat" to "817293cec9c83a112f27e65fe8d96cc507fe1447dc48b4c6693f73b854d98c2b",
            "C11.live.openai.responses" to "f88ef387a8705313f5a90b138290b6c6992cd2111f47b8861827b8f159377b75",
            "C11.live.gemini" to "2f99c63b680c460ef2028d4428ddc491ddbfddb53579c9441c1d5606ef5a4a04",
            "C11.restart.anthropic" to "b0ce264612adac8e2263903f9bc7618309a031d9751fb6df5472fb9fff4d042e",
            "C11.restart.openai.chat" to "c7fd2e14af0080976634203a5318e9dd358d868da127d3b3c19357b089b98ad8",
            "C11.restart.openai.responses" to "0676a0e40bdb7321ef57848e11d59c17e28bcb47fe321f4aa322bc05f470313f",
            "C11.restart.gemini" to "e39bb356f8c1b22e69b1d880601ce02579c64689085e7d27b86f210d8823dadc",
            "C12.live.anthropic" to "3edc78691e0d636d3cfee98872886b10ecd554583c7c387667f42ddc62728b04",
            "C12.live.openai.chat" to "c55f77781532a2f8f9798d86f4eb31964a4d86cdaf3294bcd8134354db250a7d",
            "C12.live.openai.responses" to "9c16ae61c8b9da7b9e3179b1e45d8c461e0f62a9d9299de9ebb5ed3ef1bb022f",
            "C12.live.gemini" to "682d5fe05a403207b8812672c425e03db8fdd328d3ff397a0a68414520006ad9",
            "C12.restart.anthropic" to "e8a112938816c6c85db210e49e105dd483cad87351f2eafc03f334c68a6d2aa7",
            "C12.restart.openai.chat" to "1ba43177cb45741a7ac355d4e43ba2b1ba8f45309f8023c5a2bbb463c0abb921",
            "C12.restart.openai.responses" to "729db8d5425b7ed861708df8586235ca3dd0f9e36e52f4a9f70f23927de0c9d2",
            "C12.restart.gemini" to "24d77ea900986d6fc1658381ea41bd58e862d44d27efe71100177d8d38240796",
            "C13.live.anthropic" to "3187d89c75f4886fceb9bf4b22ec64fc0024f7518d8cd9e40d65a033b481f6ff",
            "C13.live.openai.chat" to "1b2c9061fbbabdffbfaa671998fc0390e3027eb1671ca3e65dd18b2886b2b964",
            "C13.live.openai.responses" to "535f1a34ed2b3d4ee8a9cd1edea8618deb6617ba40245f37ec8d894ba0a4f77f",
            "C13.live.gemini" to "23aa8d854de88945aa9e106dad935be28be0e826a1923d8e3530ed9f32a652c5",
            "C13.restart.anthropic" to "3187d89c75f4886fceb9bf4b22ec64fc0024f7518d8cd9e40d65a033b481f6ff",
            "C13.restart.openai.chat" to "1b2c9061fbbabdffbfaa671998fc0390e3027eb1671ca3e65dd18b2886b2b964",
            "C13.restart.openai.responses" to "535f1a34ed2b3d4ee8a9cd1edea8618deb6617ba40245f37ec8d894ba0a4f77f",
            "C13.restart.gemini" to "23aa8d854de88945aa9e106dad935be28be0e826a1923d8e3530ed9f32a652c5",
            "C14.live.anthropic" to "2b4059dc01bdcad647bd9fa040d726578dc1fe1406aa39b23abb69ca9fcfa334",
            "C14.live.openai.chat" to "52811ac045e78416895166ea7fb1e323631aa3ba7636700db8021248a05eb497",
            "C14.live.openai.responses" to "8d8032aa96877bd66cc0fe1962057f56399ad01e0cb634de94deaad283b7be0d",
            "C14.live.gemini" to "c29626f22e0dd916facc3e4d32fbff9c655ad4f5057c7e95de19864f5708f36e",
            "C14.restart.anthropic" to "2b4059dc01bdcad647bd9fa040d726578dc1fe1406aa39b23abb69ca9fcfa334",
            "C14.restart.openai.chat" to "52811ac045e78416895166ea7fb1e323631aa3ba7636700db8021248a05eb497",
            "C14.restart.openai.responses" to "8d8032aa96877bd66cc0fe1962057f56399ad01e0cb634de94deaad283b7be0d",
            "C14.restart.gemini" to "c29626f22e0dd916facc3e4d32fbff9c655ad4f5057c7e95de19864f5708f36e",
            "C15.live.anthropic" to "947783212957cf79371906edd707ddab154b26c03aff75977ba9b576351cb8a8",
            "C15.live.openai.chat" to "c50a6add63b95c4ff3ee1cbde267ba7b3fa7d20fa4881fa11b5c447fd92d2993",
            "C15.live.openai.responses" to "ffc53c0e1afa1cff0ce0658e289d7a41089c6ba83d20175fa9ec6608060f536c",
            "C15.live.gemini" to "c1ee99de62694d751e457ef8b20d01207dc954c8570eac16f129f6caa22f0939",
            "C15.restart.anthropic" to "947783212957cf79371906edd707ddab154b26c03aff75977ba9b576351cb8a8",
            "C15.restart.openai.chat" to "c50a6add63b95c4ff3ee1cbde267ba7b3fa7d20fa4881fa11b5c447fd92d2993",
            "C15.restart.openai.responses" to "ffc53c0e1afa1cff0ce0658e289d7a41089c6ba83d20175fa9ec6608060f536c",
            "C15.restart.gemini" to "c1ee99de62694d751e457ef8b20d01207dc954c8570eac16f129f6caa22f0939",
            "C16.live.anthropic" to "aa6a39e54696936f94566396cc7c573aaacb0b01a46c5d721b036196b3030f2b",
            "C16.live.openai.chat" to "88c349bf366bbd7fa26b8621a0d282fc7d6ccd6ebda185ba7fcf6e287fd02fb7",
            "C16.live.openai.responses" to "1393f66540c21a482447146bbeba55e80143f85313363efcc337d2f956d5bc2f",
            "C16.live.gemini" to "776c1bb7d211a5e4abd42aedc8ee9454302d8cbca18a4a2a108004c5c88a7aa0",
            "C16.restart.anthropic" to "7b9f9c9e6d97f7ee38ee18504d67a2dba491dc4347531496237fd4c397631e05",
            "C16.restart.openai.chat" to "292c896f8235c0c25707c2145cfcdf9eca9ea8e671d7950c0e5d60192d5ef6cf",
            "C16.restart.openai.responses" to "24a10cf7640002c810a5965e81f82cf6f97afbae231adf55381eb76535a4e31c",
            "C16.restart.gemini" to "4ccf7c8d1ef94a7883712c769d902e2643da3472cbf32c1087cc00ec3574e0ed",
            "C17.live.anthropic" to "3f229e46859d84242bb2b76b06319185074fb74bdfed29aeba3d17c7b864d979",
            "C17.live.openai.chat" to "f1855b926871ef243695d1f96dc6ff1845bd3e1f667527b987938fdc3ced4f2f",
            "C17.live.openai.responses" to "9ad10249ddb1a764300c9150333d86661bfc16f749b104a31bd2e047e026a954",
            "C17.live.gemini" to "430829fb69f932f7790389625e981730fdc7fd57d51036cbe0b8e0c6d31ca65f",
            "C17.restart.anthropic" to "f49e4318ed442c18022d56c2df20a01c1be2a8636eeea0607a36941bd058353b",
            "C17.restart.openai.chat" to "61798cdc312f7d5383d5ff5a05d06cbbc7e1e84a9936eabcf2a556bdf8d98b15",
            "C17.restart.openai.responses" to "d4330e102682a8782c0e10fba17d8e668f47a96f0bea01f8dc6575551766538d",
            "C17.restart.gemini" to "006a990a607170d2304e5311b829fddfa0cffd18242f90051c24c7de14c8e7ce",
            "C18.live.anthropic" to "db4f1d370e6c9b4943a51240b80b69f13a87042cd64152090eb1ab332676ab40",
            "C18.live.openai.chat" to "12c60b591df0828fba8f9aa1524624641b556862b764c437571dad71487ed39e",
            "C18.live.openai.responses" to "a253695ecb592683b63a7697bfe2cc0d2b18101b849e6455403ec4b3bcb4e250",
            "C18.live.gemini" to "e221d2a28da6850fe57103054feb240544644cbe44df5e0ff3c6516e2affb9e3",
            "C18.restart.anthropic" to "209ea23ef313f9e3f6b625f4c0a1d8e7a5af7b852297f777ce1c7c4db9b4d315",
            "C18.restart.openai.chat" to "ff3500fbace86e2ff3d7833f399262395e1099582273bea2c55638b5df5f296a",
            "C18.restart.openai.responses" to "9f45b63299a57452d2110b683a51ff509281673c91466e73cb42a25da6d10fe0",
            "C18.restart.gemini" to "d7766cf9cbc589c26ad41524d39636a31ba77fbf85641a078f4f6aba375c1e33",
            "C19.live.anthropic" to "d2e8cb790f979717ea4070d81657d2ffeffb852bc72ba18b519457db0de9582d",
            "C19.live.openai.chat" to "32363fa75021f94984707e10b039247676b02941eb14f4d44957135a10c94980",
            "C19.live.openai.responses" to "fb8480c2fa481cd16aa619f5767ca3366c02962050e3a7b76eb703aa81973f6a",
            "C19.live.gemini" to "df0f0a9416ac440740a3ecd48e2987ca8507ee2d69d99c5fc23ad3cbc0c01ca8",
            "C19.restart.anthropic" to "2ad1918b23bb44191cbc8a2e3e5a5aa902c4706c7c5fcc99ce3b6f040ab030f6",
            "C19.restart.openai.chat" to "5e520db5adb66b5335182af2e13bdabb94d0003080c6cd653e2a5e1753e21fa3",
            "C19.restart.openai.responses" to "dc41615b81a46dd2de96f685d5e22c374ef09124bc8e6d0fa46453119bdd0fee",
            "C19.restart.gemini" to "1a1f8d9d3391d5ff337782ad8e7081d6e43add17b0d26cf25b39259ba865265c",
            "C20.live.anthropic" to "1057fb46c80c0e6c26e97f24f19c778d34b6da205121ee387c2d6c5bdc69db2d",
            "C20.live.openai.chat" to "7987b204450ac10b5ade70de458fa5968736acaa26f3c8987e542c7a3d87f7b1",
            "C20.live.openai.responses" to "7cd69848636ad834ffbf6a0f77af893d5e225cf7ed3f58cb4c891338e7f5a1ad",
            "C20.live.gemini" to "94fadb7e8452516a372a63d0dbde7938296367ea839463c82ef0444a535be22f",
            "C20.restart.anthropic" to "1057fb46c80c0e6c26e97f24f19c778d34b6da205121ee387c2d6c5bdc69db2d",
            "C20.restart.openai.chat" to "7987b204450ac10b5ade70de458fa5968736acaa26f3c8987e542c7a3d87f7b1",
            "C20.restart.openai.responses" to "7cd69848636ad834ffbf6a0f77af893d5e225cf7ed3f58cb4c891338e7f5a1ad",
            "C20.restart.gemini" to "94fadb7e8452516a372a63d0dbde7938296367ea839463c82ef0444a535be22f",
            "C21.live.anthropic" to "8763e919efd80cfb8a4a62d6c34d6305b3b967468ab8b982531c5f4bd8f27e2e",
            "C21.live.openai.chat" to "53b7b45b8ce44581d61e5bbcc8b4826c7198b027271146e159ca339ee5b84219",
            "C21.live.openai.responses" to "5b45e74f46749c995e1535d59c0e9453b8d67722b5aa6f93bcfdfda45ab248c4",
            "C21.live.gemini" to "2c5090f5c1610312ad596037bc558a8d31f9ae6a1047ad882418e65c6bf11988",
            "C21.restart.anthropic" to "939c9a732293c277136d23c3d7df02829f9b3e5d78201388111410f12df95be4",
            "C21.restart.openai.chat" to "2a18755a969c700d56dca983739e47bc71b1d4b4762670f3be0c30cc6f080b1a",
            "C21.restart.openai.responses" to "b0e56c1fd00241b8f3d9a92decc05bef8fef9c042fe5913c81e9db5bdd323365",
            "C21.restart.gemini" to "5c547682dbf4d352fa6b030c907f330232a776163a5e979323a748660c0bc47b",
            "C22.live.anthropic" to "8714e15effb404dc83f2448172f826f0f13c6e4340ac2ae62b67cd5bc21ea9d7",
            "C22.live.openai.chat" to "ccbbd5fbcab438a654c6f05923eb161ddea139c9c76df14ae53e09b0f4a81399",
            "C22.live.openai.responses" to "f0a5d81ce74bd3905b24701ab5a059e498f000760bb99e1fe0d162c083652a3b",
            "C22.live.gemini" to "58f750a8a5c8e399697204445a1ee2473895b625035e675098ea06a0ddc60e95",
            "C22.restart.anthropic" to "d071722de3973f83b957264e85e151be37f74c7a5faf629c00d757cbb1b96896",
            "C22.restart.openai.chat" to "598f61f59bd3b589b05f60f7cc36ff343e93f5fcc0e94ef158d680b53956cc04",
            "C22.restart.openai.responses" to "be0dedb7c1725dd194a9d74b1f8fd41137a805b9989e34cf5151695de6d5cf8a",
            "C22.restart.gemini" to "9b4a0958fc646b739e03bd84c275d9f3a81fc53a97452c5d84e0d4d663e7892a",
            "C23.live.anthropic" to "a02ae4afdc9cd0c01a8ac738b305dea89866504c6844aaaab8fc9c9e8b76963b",
            "C23.live.openai.chat" to "6a2b7097d64d457309cdbd6f4a9a4ee277992591aaf00075d855549479a4b0f5",
            "C23.live.openai.responses" to "ee6195cac9b74d85c97639b5190cac520aa48a8331378fc434be4cd238386bdc",
            "C23.live.gemini" to "ad94051bc851937f3777369822064e72ee02394e5a5d774bc69b443df7d5ddea",
            "C23.restart.anthropic" to "324918153ebc1ed485419d165a67b48b53347347cf10f0e0a61f24eb3c2aeffc",
            "C23.restart.openai.chat" to "d0bbd21e508562b265ce3c0ab9584843f13cc11f73b0ddc890afcac221b4f13c",
            "C23.restart.openai.responses" to "c0346a34df25b01d7459bff0bddc9fd331b2bb4eea96ca2e2938cf1e5ee8984e",
            "C23.restart.gemini" to "3af857d4f91fd5f9e209e9e00940c45ce795e63ee0a6c5a3bf16b29f5aaccc09",
            "C24.live.anthropic" to "7c1d6508ebb4d3a1e401b0765980790bf5c584d5eb8a52fb640c07a1125179d4",
            "C24.live.openai.chat" to "48de096dda4fccad2f9bb06d827343919dca08f6fdc57e09030d332c8850c8ee",
            "C24.live.openai.responses" to "433af6c602be89f10482a9cb1bcdaf504c9717cf39c0f69b0e6f418c68def93c",
            "C24.live.gemini" to "fab91eefd83043b7e065f3c00f7829504c54de135e3be691b0d35a9695d311a2",
            "C24.restart.anthropic" to "5dccf7623c71b708e5c270250d7f214968b8950582685138d144edf5794cddfe",
            "C24.restart.openai.chat" to "b02d75c15d7026058b8248fd0bfa3b552fa6eaa159c1390f3c2da1130030ebdf",
            "C24.restart.openai.responses" to "2fd646d86881080042eb84bbe186bd1198edabb20029c1a3d7bb57cd75e6e2e4",
            "C24.restart.gemini" to "62b6f24842553c9042de42b8512ffddf484d7a808785f05294c0287f58c7ca6b",
            "C25.live.anthropic" to "0b6852bb2fc1e5d62e6ca619a2920fd57143134410e67d472bd31f1c71a26f40",
            "C25.live.openai.chat" to "a7094a7789c3d6dc2c55877e7520521655e882ce8638da52eec223a3cf29ff9c",
            "C25.live.openai.responses" to "49415e010e6040f8d04dc3afcff21219729bb5b9ce5438ebfe74e8979e191da2",
            "C25.live.gemini" to "812f813f2f176f810a0a5c79c6b5d2274cebb402feb792c2ae4c7baf07097266",
            "C25.restart.anthropic" to "0b6852bb2fc1e5d62e6ca619a2920fd57143134410e67d472bd31f1c71a26f40",
            "C25.restart.openai.chat" to "a7094a7789c3d6dc2c55877e7520521655e882ce8638da52eec223a3cf29ff9c",
            "C25.restart.openai.responses" to "49415e010e6040f8d04dc3afcff21219729bb5b9ce5438ebfe74e8979e191da2",
            "C25.restart.gemini" to "812f813f2f176f810a0a5c79c6b5d2274cebb402feb792c2ae4c7baf07097266",
            "C26.live.anthropic" to "009f084b4a063cf3185561a08f3cdfa702c55c856f70a3274869ff53d07dfe14",
            "C26.live.openai.chat" to "9c97cf680ccaf55f06126a932a4a1845ea27aecdbe8bfe69b076dbf50c1dc968",
            "C26.live.openai.responses" to "fade33781736996accca336dae22d8dc94ed2ec343ec296396997b8c78df5502",
            "C26.live.gemini" to "7063d10582a4d01393e44b06584188e586327446c1ba4a1285800a2e8095d270",
            "C26.restart.anthropic" to "009f084b4a063cf3185561a08f3cdfa702c55c856f70a3274869ff53d07dfe14",
            "C26.restart.openai.chat" to "9c97cf680ccaf55f06126a932a4a1845ea27aecdbe8bfe69b076dbf50c1dc968",
            "C26.restart.openai.responses" to "fade33781736996accca336dae22d8dc94ed2ec343ec296396997b8c78df5502",
            "C26.restart.gemini" to "7063d10582a4d01393e44b06584188e586327446c1ba4a1285800a2e8095d270",
            "C27.live.anthropic" to "f3cbe6be1c531664f963f88d1d7e994e2e4cf956d9249a99d965d75b559a153c",
            "C27.live.openai.chat" to "9b9230f24c3deaf88debbaae429031712ffa188972e019759694b00b1aed6d38",
            "C27.live.openai.responses" to "560ee82bcb8721fd64e258325995f9ee9893b1a77e236edd983894167e2bfb8b",
            "C27.live.gemini" to "074cd69d3e4373758f32473c1dfaad3d25a637ca154796982efe3b2a96177fb8",
            "C27.restart.anthropic" to "040c367b4e6a1dc7653e811258c23e0ddf1153ae0170125ad4483757edd58db4",
            "C27.restart.openai.chat" to "d53534a0c4f097661f45f29f502b06eab6caad4b391552ad9fbbcc31aa2f5847",
            "C27.restart.openai.responses" to "98e6249764b79e25d036067a3af2247f13dc58bbaf852724905b2a7de9ca7e22",
            "C27.restart.gemini" to "261f42aabe5a8ef58b4462025538fb290af1c21aecaf760c110d5308ab77b539",
            "C28.live.anthropic" to "b44e475a0d7276874fe6e216722488d293e1b135324b922c6d32605038c4f990",
            "C28.live.openai.chat" to "7ed7de9acc49291a597c48e4fa720d3ba4720c9479416b811d65f4bbcd795f47",
            "C28.live.openai.responses" to "d3154a0eeb505a8567fb2482546cf9e9fb24724ac61d2de5af23a1e3e80ba800",
            "C28.live.gemini" to "4e122b8452f9f092031767feccc65d78106e3ee97de69dd881f6b11640c78737",
            "C28.restart.anthropic" to "b44e475a0d7276874fe6e216722488d293e1b135324b922c6d32605038c4f990",
            "C28.restart.openai.chat" to "7ed7de9acc49291a597c48e4fa720d3ba4720c9479416b811d65f4bbcd795f47",
            "C28.restart.openai.responses" to "d3154a0eeb505a8567fb2482546cf9e9fb24724ac61d2de5af23a1e3e80ba800",
            "C28.restart.gemini" to "4e122b8452f9f092031767feccc65d78106e3ee97de69dd881f6b11640c78737",
            "C29.live.anthropic" to "57967fbda3472219a99a73238c9aba697a8c3d5feadbd0f4a1b33b744f33c8e5",
            "C29.live.openai.chat" to "b943dbf9d1ca3618e720d09fdc45bebd7b55b856ee4ffbba0d2d88c1c00a8d39",
            "C29.live.openai.responses" to "5be274abb432a734ca74e186edfae0b888725f70123411edabf30baff5d94218",
            "C29.live.gemini" to "f59a986853f958314e58b7b6d0542cd144169fd2b671eadfd906cb254a1e8f5c",
            "C29.restart.anthropic" to "7193f04a4ae462a10bd3980944a39f8ff579a4ab9d3a83f93578d1c46d280d4a",
            "C29.restart.openai.chat" to "0bd42269f923cedf82a8a6318b1fb17ffdcdd57216cb0b1bbce755f0d67ccaea",
            "C29.restart.openai.responses" to "92486e3e1cbc28adcec96ddae35a19ce39ecbd9b68004b5cda0f4265975f6886",
            "C29.restart.gemini" to "b44d85e178dacd2733d2481092d46d6badd90de6500bae1404f576728070d236",
            "C30.live.anthropic" to "a371e5ec15606eaf3ec5abad35910f7bd71ed63df6203360452191b11eebecf2",
            "C30.live.openai.chat" to "d413b6e8806b3ccf59733469f4795b88eecf06c9142e6e887257188a60e517b0",
            "C30.live.openai.responses" to "6bed5f75a9fed15b68021496b95138a054b8915f22fa62397ecd32946b273fab",
            "C30.live.gemini" to "cc610a1fe0a6fa3f2fc85b27406de0606b144df7a7297dc98fdf74fb25d26ae4",
            "C30.restart.anthropic" to "ccd5c99d3be2b9436ee97c8c583b42100c95dbfbe622491cbc9939cee06c6529",
            "C30.restart.openai.chat" to "a04df0c4b9e08795b8d46e965b1474c47dab9012e902b02f49292cc9ace3fa42",
            "C30.restart.openai.responses" to "438b3ef2c14b9b365fbf31303caf66b48079ae4e131bdc38d6e869541b5ec635",
            "C30.restart.gemini" to "154e5ad3c80cf007446b09eac170e7b8f9a938745d8fea3699302d032ce83272",
            "C31.live.anthropic" to "15b54d001961076a6929c379788997ea15a403e747ba6adf334bc7be0ab19c21",
            "C31.live.openai.chat" to "bd94a5e39ec07737db207d495838247cd375923df3dd55bc560d03422f59c69b",
            "C31.live.openai.responses" to "538d04380ddc1166f7d8d3bd1d3426a84c48a22efff58995b6da25174b9f9fe2",
            "C31.live.gemini" to "3d67b65f1ee9ffcbaa2b2c2307b0a7d2df9e08397d8b316435a8502641dfdfab",
            "C31.restart.anthropic" to "12704351517e13d056eef6ff8baa51b6b39a534675a85c1fa915016affd0d5db",
            "C31.restart.openai.chat" to "d1a24a2265657d5475fc01bad9b61ce7859bb0c219287764244e298d575e15aa",
            "C31.restart.openai.responses" to "3510a679b1226358209e24a8d8cc8546d222e12d14c12230a4a614671cd598f8",
            "C31.restart.gemini" to "e2b81f746767ba2cd5fee22484ade16e7c24c19f57805ec375db404ee2ee2ba9",
            "C32.live.anthropic" to "777ce82e941586d3ef0a0d32b4a894f4d0a9210bea6c614a17f83569c02441c1",
            "C32.live.openai.chat" to "2ebc3cb0a67b7fe7bd09fcd269407139dd118709ce5bcc7bb3d20c9fd3158a0a",
            "C32.live.openai.responses" to "b5088078d6e3812680b52aae5cb0535db30c2dd7e1630ed230c92e68948f0d46",
            "C32.live.gemini" to "9bced4e2c8bddc171bf606d017531a872a8bda4ae480f4afab697de33d8af90e",
            "C32.restart.anthropic" to "f8b3fe0215789874c49ef07cf0bdb044bfda52920acfebf1f898b118f2ac7d50",
            "C32.restart.openai.chat" to "86c77d659a10516cda95f3e2a98d359080f7dc5d0834c550eb7cc5cef5ac17a2",
            "C32.restart.openai.responses" to "cc32c16a18fd7d5ca6326ae66261f7baa66dd98e5ed5e08a9dcf33e954f1a22a",
            "C32.restart.gemini" to "03cbc4c06db2f0cb83799e1491ac9e829ad09715cc8e4db59a34ccc8cff221d6",
            "P-a5.live.anthropic" to "a6baae3f41ba71e6ba92be264fe5304a86b499d88cca25e3c730923df0936027",
            "P-a5.live.openai.chat" to "804e183f9d7c5098c30050501142381aca98e7949f5a1c19be9a7c3a87f4e2cd",
            "P-a5.live.openai.responses" to "b4e27a282567e8835ebfbd85bf8998ecfeba3e44f1e8b39595d0ce0646d27315",
            "P-a5.live.gemini" to "f1424268a36eb6630a8a64b8bd73f7afed1b94d4efe7b59578f1dd66ef6434bf",
        )

        /** Bodies a fix is meant to change, with the fix that changes them. */
        private val CHANGED_BY_FIX: Map<String, String> = mapOf(
            "C26.live.anthropic" to "fix 1 (#404): summary after the carrier's tool_result; Anthropic / Gemini put results first",
            "C26.live.gemini" to "fix 1 (#404): summary after the carrier's tool_result; Anthropic / Gemini put results first",
            "C26.restart.anthropic" to "fix 1 (#404): summary after the carrier's tool_result; Anthropic / Gemini put results first",
            "C26.restart.gemini" to "fix 1 (#404): summary after the carrier's tool_result; Anthropic / Gemini put results first",
            "P-a5.live.anthropic" to "fix 2 (P-a5): the budget measures the gate's output, so all six BMPs go out",
            "P-a5.live.openai.chat" to "fix 2 (P-a5): the budget measures the gate's output, so all six BMPs go out",
            "P-a5.live.openai.responses" to "fix 2 (P-a5): the budget measures the gate's output, so all six BMPs go out",
            "P-a5.live.gemini" to "fix 2 (P-a5): the budget measures the gate's output, so all six BMPs go out",
            "C23.live.anthropic" to "T-readimage-fail-visible (iOS 1eb3e2bef): the undecodable HEIC's read_image result now says it exists and its size",
            "C23.live.openai.chat" to "T-readimage-fail-visible (iOS 1eb3e2bef): the undecodable HEIC's read_image result now says it exists and its size",
            "C23.live.openai.responses" to "T-readimage-fail-visible (iOS 1eb3e2bef): the undecodable HEIC's read_image result now says it exists and its size",
            "C23.live.gemini" to "T-readimage-fail-visible (iOS 1eb3e2bef): the undecodable HEIC's read_image result now says it exists and its size",
            "C23.restart.anthropic" to "T-readimage-fail-visible (iOS 1eb3e2bef): the undecodable HEIC's read_image result now says it exists and its size",
            "C23.restart.openai.chat" to "T-readimage-fail-visible (iOS 1eb3e2bef): the undecodable HEIC's read_image result now says it exists and its size",
            "C23.restart.openai.responses" to "T-readimage-fail-visible (iOS 1eb3e2bef): the undecodable HEIC's read_image result now says it exists and its size",
            "C23.restart.gemini" to "T-readimage-fail-visible (iOS 1eb3e2bef): the undecodable HEIC's read_image result now says it exists and its size",
        )
    }

    private fun hash(body: org.json.JSONObject, root: String) =
        ImageRealFixtures.sha256(body.toString().replace(root, "<ROOT>"))

    private fun bodies(): Map<String, String> {
        val out = linkedMapOf<String, String>()
        ImageRealJvmCodec.with { _ ->
            if (id == "P-a5") {
                CtxSession("pa5").use { s ->
                    for (i in 1..6) {
                        s.user("bmp $i", listOf(CtxKit.image("b$i.bmp", CtxKit.bmp(1100, 1100), "image/bmp")))
                        s.assistant("ok $i")
                    }
                    s.user("compare")
                    val ctx = s.request()
                    for (p in CtxProvider.entries) out["P-a5.live.${p.id}"] = hash(CtxSession.wire(p, ctx.messages), s.root.absolutePath)
                }
                return@with
            }
            val sc = CtxScenarios.all.single { it.id == id }
            CtxSession(sc.id.lowercase(), sc.window).use { s ->
                sc.build(s)
                val liveCtx = hashPhase("live", s, out)
                // beforeRestart only records facts and edits storage; it needs no bodies.
                sc.beforeRestart(s, CtxRun(sc, s, CtxPhase("live", liveCtx, emptyMap(), s.history.toList()), null))
                sc.restart?.let { it(s); hashPhase("restart", s, out) }
            }
        }
        return out
    }

    /** One provider body at a time: a C24 body is ~29 MB of base64, so none is kept. */
    private fun hashPhase(name: String, s: CtxSession, out: MutableMap<String, String>): com.openminis.app.ui.chat.RequestContextBuilder.RequestContext {
        val ctx = s.request()
        for (p in CtxProvider.entries) out["$id.$name.${p.id}"] = hash(CtxSession.wire(p, ctx.messages), s.root.absolutePath)
        return ctx
    }

    @Test fun `only the bodies the fixes target differ from before`() {
        val now = bodies()
        for ((k, v) in now) println("[WIRE] \"$k\" to \"$v\",")
        val before = BEFORE.filterKeys { it.startsWith("$id.") }
        assertEquals(before.keys, now.keys)
        val changed = now.keys.filter { now[it] != before[it] }.toSet()
        println("[WIRE] $id changed: $changed")
        assertEquals("bodies that changed vs the fix list", CHANGED_BY_FIX.keys.filter { it.startsWith("$id.") }.toSet(), changed)
    }
}
