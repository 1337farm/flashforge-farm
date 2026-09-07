package com.flashforge.farm.modelrepo

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream

class ModelMigrator(private val context: Context, private val transport: IrohModelTransport) {
    private val TAG = "ModelMigrator"

    interface Callback {
        fun onProgress(model: String, stage: String, percent: Int)
        fun onCompleted(model: String, modelHash: String)
        fun onError(model: String, error: String)
        fun onAllDone()
    }

    fun migrateAll(callback: Callback) {
        Thread {
            try {
                for ((fileName, metadataJson) in MODELS) {
                    try {
                        callback.onProgress(fileName, "Reading", 10)
                        val file = File(File(context.filesDir, "models"), fileName)
                        if (!file.exists()) {
                            callback.onError(fileName, "File not found: models/" + fileName)
                            continue
                        }
                        val data = readAll(file)
                        callback.onProgress(fileName, "Publishing", 50)
                        val datas = mutableListOf<ByteArray>()
                        datas.add(data)
                        val ticket = transport.publishModel(metadataJson, datas)
                        callback.onCompleted(fileName, ticket)
                    } catch (e: Exception) {
                        Log.e(TAG, "migrate failed", e)
                        callback.onError(fileName, e.message ?: "failed")
                    }
                }
                callback.onAllDone()
            } catch (e: Exception) {
                Log.e(TAG, "Migration failed", e)
            }
        }.start()
    }

    private fun readAll(file: File): ByteArray {
        FileInputStream(file).use { fis ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(32768)
            var n = fis.read(buf)
            while (n != -1) {
                out.write(buf, 0, n)
                n = fis.read(buf)
            }
            return out.toByteArray()
        }
    }

    companion object {
        private val MODELS = listOf(
            Pair("3dbenchy.stl", "{\"schema\":1,\"title\":\"3D Benchy\",\"description\":\"Standard calibration torture test: overhangs, bridging, stringing, dimensional accuracy.\",\"designer\":{\"name\":\"Unknown\",\"pubkey\":\"\"},\"license\":{\"spdx\":\"unspecified\",\"url\":\"\"},\"category\":\"calibration\",\"tags\":[\"calibration\",\"test\"],\"images\":[],\"files\":[\"3dbenchy.stl\"],\"printSettings\":{\"layerHeight\":0.2,\"infill\":0.2,\"supports\":false,\"notes\":\"\"},\"remixOf\":null,\"verification\":\"a0afa505090b6f16cb6bdcfad3b843ec5b3b540b8357c306d447d0b324b051bc\"}"),
            Pair("OrcaCube_v2.stl", "{\"schema\":1,\"title\":\"Orca Cube v2\",\"description\":\"OrcaSlicer calibration cube with embossed markers.\",\"designer\":{\"name\":\"Unknown\",\"pubkey\":\"\"},\"license\":{\"spdx\":\"unspecified\",\"url\":\"\"},\"category\":\"calibration\",\"tags\":[\"calibration\",\"cube\"],\"images\":[],\"files\":[\"OrcaCube_v2.stl\"],\"printSettings\":{\"layerHeight\":0.2,\"infill\":0.2,\"supports\":false,\"notes\":\"\"},\"remixOf\":null,\"verification\":\"254271df6c378a54f2c722f3c18295c7a6fb27729579295f320b2c47ea08841a\"}"),
            Pair("OrcaPlug_v2.stl", "{\"schema\":1,\"title\":\"Orca Plug v2\",\"description\":\"OrcaSlicer tolerance and fit test plug.\",\"designer\":{\"name\":\"Unknown\",\"pubkey\":\"\"},\"license\":{\"spdx\":\"unspecified\",\"url\":\"\"},\"category\":\"calibration\",\"tags\":[\"calibration\",\"tolerance\"],\"images\":[],\"files\":[\"OrcaPlug_v2.stl\"],\"printSettings\":{\"layerHeight\":0.2,\"infill\":0.2,\"supports\":false,\"notes\":\"\"},\"remixOf\":null,\"verification\":\"39b38af659c33286c321aa5fdd5c1d554c422f623b139fa72ae768ebe84ca6c0\"}"),
            Pair("OrcaToleranceTest.stl", "{\"schema\":1,\"title\":\"Orca Tolerance Test\",\"description\":\"OrcaSlicer tolerance test piece.\",\"designer\":{\"name\":\"Unknown\",\"pubkey\":\"\"},\"license\":{\"spdx\":\"unspecified\",\"url\":\"\"},\"category\":\"calibration\",\"tags\":[\"calibration\",\"tolerance\"],\"images\":[],\"files\":[\"OrcaToleranceTest.stl\"],\"printSettings\":{\"layerHeight\":0.2,\"infill\":0.2,\"supports\":false,\"notes\":\"\"},\"remixOf\":null,\"verification\":\"19f3a8e33ec2c61643a69724773bb11aaaa2e4a487ccad23b36194e8900dea13\"}"),
            Pair("Orca_stringhell.stl", "{\"schema\":1,\"title\":\"String Hell\",\"description\":\"Stringing and retraction test with fine spikes.\",\"designer\":{\"name\":\"Unknown\",\"pubkey\":\"\"},\"license\":{\"spdx\":\"unspecified\",\"url\":\"\"},\"category\":\"calibration\",\"tags\":[\"calibration\",\"stringing\"],\"images\":[],\"files\":[\"Orca_stringhell.stl\"],\"printSettings\":{\"layerHeight\":0.2,\"infill\":0.2,\"supports\":false,\"notes\":\"\"},\"remixOf\":null,\"verification\":\"d6a70b1e2085ca7e400b8d800fae9bbd078420b27a671d99ee1a0230f1e09d4c\"}"),
            Pair("Stanford_Bunny.stl", "{\"schema\":1,\"title\":\"Stanford Bunny\",\"description\":\"Classic Stanford bunny scan mesh, good overhang demo.\",\"designer\":{\"name\":\"Unknown\",\"pubkey\":\"\"},\"license\":{\"spdx\":\"unspecified\",\"url\":\"\"},\"category\":\"demo\",\"tags\":[\"demo\",\"organic\"],\"images\":[],\"files\":[\"Stanford_Bunny.stl\"],\"printSettings\":{\"layerHeight\":0.2,\"infill\":0.2,\"supports\":false,\"notes\":\"\"},\"remixOf\":null,\"verification\":\"f4626bbd72d2f7140bcc49ac7fa4c7e49c18b7cb25f6a4438e19482860eeb202\"}"),
            Pair("Voron_Design_Cube_v7.stl", "{\"schema\":1,\"title\":\"Voron Design Cube v7\",\"description\":\"Voron Design Cube, dimensional accuracy check.\",\"designer\":{\"name\":\"Unknown\",\"pubkey\":\"\"},\"license\":{\"spdx\":\"unspecified\",\"url\":\"\"},\"category\":\"calibration\",\"tags\":[\"calibration\",\"cube\"],\"images\":[],\"files\":[\"Voron_Design_Cube_v7.stl\"],\"printSettings\":{\"layerHeight\":0.2,\"infill\":0.2,\"supports\":false,\"notes\":\"\"},\"remixOf\":null,\"verification\":\"166c9c233cde4ec674cb37292b60e4ddca2141cc57e7fa47af91f999c63c4201\"}"),
            Pair("box.stl", "{\"schema\":1,\"title\":\"Box\",\"description\":\"Simple box primitive for first-layer checks.\",\"designer\":{\"name\":\"Unknown\",\"pubkey\":\"\"},\"license\":{\"spdx\":\"unspecified\",\"url\":\"\"},\"category\":\"primitive\",\"tags\":[\"primitive\",\"test\"],\"images\":[],\"files\":[\"box.stl\"],\"printSettings\":{\"layerHeight\":0.2,\"infill\":0.2,\"supports\":false,\"notes\":\"\"},\"remixOf\":null,\"verification\":\"3c7506de71f96b8391a809da650a00a40fe14c1328905748ef24d199116d3dfc\"}"),
            Pair("bunny.stl", "{\"schema\":1,\"title\":\"Bunny\",\"description\":\"Low-poly bunny placeholder.\",\"designer\":{\"name\":\"Unknown\",\"pubkey\":\"\"},\"license\":{\"spdx\":\"unspecified\",\"url\":\"\"},\"category\":\"demo\",\"tags\":[\"demo\",\"placeholder\"],\"images\":[],\"files\":[\"bunny.stl\"],\"printSettings\":{\"layerHeight\":0.2,\"infill\":0.2,\"supports\":false,\"notes\":\"\"},\"remixOf\":null,\"verification\":\"4a222346223cf2c207c34d7a3d4e8ea297b004ff862b06b6e4c7c2eeac9f761a\"}"),
            Pair("calicat.stl", "{\"schema\":1,\"title\":\"Cali Cat\",\"description\":\"Cal terrain cat (calibration cat with terrain base).\",\"designer\":{\"name\":\"Unknown\",\"pubkey\":\"\"},\"license\":{\"spdx\":\"unspecified\",\"url\":\"\"},\"category\":\"calibration\",\"tags\":[\"calibration\",\"cat\"],\"images\":[],\"files\":[\"calicat.stl\"],\"printSettings\":{\"layerHeight\":0.2,\"infill\":0.2,\"supports\":false,\"notes\":\"\"},\"remixOf\":null,\"verification\":\"d65709b6cd77f467b2a71e4298884e229d878540fd16cca70684cebf45698ee0\"}"),
            Pair("cone.stl", "{\"schema\":1,\"title\":\"Cone\",\"description\":\"Cone primitive for vase-mode tests.\",\"designer\":{\"name\":\"Unknown\",\"pubkey\":\"\"},\"license\":{\"spdx\":\"unspecified\",\"url\":\"\"},\"category\":\"primitive\",\"tags\":[\"primitive\",\"vase\"],\"images\":[],\"files\":[\"cone.stl\"],\"printSettings\":{\"layerHeight\":0.2,\"infill\":0.2,\"supports\":false,\"notes\":\"\"},\"remixOf\":null,\"verification\":\"55bce1fe223cb307d0de3f1cab10de22cebba12321618ac01e2a51b2ea9aff9a\"}"),
            Pair("cylinder.stl", "{\"schema\":1,\"title\":\"Cylinder\",\"description\":\"Cylinder primitive for flow checks.\",\"designer\":{\"name\":\"Unknown\",\"pubkey\":\"\"},\"license\":{\"spdx\":\"unspecified\",\"url\":\"\"},\"category\":\"primitive\",\"tags\":[\"primitive\",\"test\"],\"images\":[],\"files\":[\"cylinder.stl\"],\"printSettings\":{\"layerHeight\":0.2,\"infill\":0.2,\"supports\":false,\"notes\":\"\"},\"remixOf\":null,\"verification\":\"b57854b22b22c64366941beb4dfbf1d44710d1c9048aadad16d813514384f047\"}"),
            Pair("fox.stl", "{\"schema\":1,\"title\":\"Fox\",\"description\":\"Low-poly fox placeholder.\",\"designer\":{\"name\":\"Unknown\",\"pubkey\":\"\"},\"license\":{\"spdx\":\"unspecified\",\"url\":\"\"},\"category\":\"demo\",\"tags\":[\"demo\",\"placeholder\"],\"images\":[],\"files\":[\"fox.stl\"],\"printSettings\":{\"layerHeight\":0.2,\"infill\":0.2,\"supports\":false,\"notes\":\"\"},\"remixOf\":null,\"verification\":\"fe77e04c78ff79040bd7005d0ca6076d4d926dd253e6da8ae3f45699982485ef\"}"),
            Pair("ksr_fdmtest_v4.stl", "{\"schema\":1,\"title\":\"Autodesk FDM Test\",\"description\":\"Autodesk Kickstarter FDM torture test.\",\"designer\":{\"name\":\"Unknown\",\"pubkey\":\"\"},\"license\":{\"spdx\":\"unspecified\",\"url\":\"\"},\"category\":\"calibration\",\"tags\":[\"calibration\",\"test\"],\"images\":[],\"files\":[\"ksr_fdmtest_v4.stl\"],\"printSettings\":{\"layerHeight\":0.2,\"infill\":0.2,\"supports\":false,\"notes\":\"\"},\"remixOf\":null,\"verification\":\"10834f191b10cbeb757cdddf99e65cf9e5bb561e74930254e77007eaa0957325\"}"),
            Pair("pa_test.stl", "{\"schema\":1,\"title\":\"PA Test\",\"description\":\"Pressure-advance calibration tower pattern.\",\"designer\":{\"name\":\"Unknown\",\"pubkey\":\"\"},\"license\":{\"spdx\":\"unspecified\",\"url\":\"\"},\"category\":\"calibration\",\"tags\":[\"calibration\",\"pressure-advance\"],\"images\":[],\"files\":[\"pa_test.stl\"],\"printSettings\":{\"layerHeight\":0.2,\"infill\":0.2,\"supports\":false,\"notes\":\"\"},\"remixOf\":null,\"verification\":\"213557fa02d3f37adaa7b00a361929fdd5323e6f393908f34012f1d81d3a89da\"}"),
            Pair("pyramid.stl", "{\"schema\":1,\"title\":\"Pyramid\",\"description\":\"Pyramid primitive for seam and corner checks.\",\"designer\":{\"name\":\"Unknown\",\"pubkey\":\"\"},\"license\":{\"spdx\":\"unspecified\",\"url\":\"\"},\"category\":\"primitive\",\"tags\":[\"primitive\",\"test\"],\"images\":[],\"files\":[\"pyramid.stl\"],\"printSettings\":{\"layerHeight\":0.2,\"infill\":0.2,\"supports\":false,\"notes\":\"\"},\"remixOf\":null,\"verification\":\"7ab867a9b76f246efdb9a09b6180415e5e5e7c54947c7fc5db01f07b60be64e6\"}"),
            Pair("sphere.stl", "{\"schema\":1,\"title\":\"Sphere\",\"description\":\"Sphere primitive for overhang curve checks.\",\"designer\":{\"name\":\"Unknown\",\"pubkey\":\"\"},\"license\":{\"spdx\":\"unspecified\",\"url\":\"\"},\"category\":\"primitive\",\"tags\":[\"primitive\",\"test\"],\"images\":[],\"files\":[\"sphere.stl\"],\"printSettings\":{\"layerHeight\":0.2,\"infill\":0.2,\"supports\":false,\"notes\":\"\"},\"remixOf\":null,\"verification\":\"77265a76b01ce41bac8fc3342cfb5db1e502c39c7884918e8e06983834818a4b\"}"),
            Pair("xyz_cube.stl", "{\"schema\":1,\"title\":\"XYZ Cube\",\"description\":\"20mm XYZ calibration cube.\",\"designer\":{\"name\":\"Unknown\",\"pubkey\":\"\"},\"license\":{\"spdx\":\"unspecified\",\"url\":\"\"},\"category\":\"calibration\",\"tags\":[\"calibration\",\"cube\"],\"images\":[],\"files\":[\"xyz_cube.stl\"],\"printSettings\":{\"layerHeight\":0.2,\"infill\":0.2,\"supports\":false,\"notes\":\"\"},\"remixOf\":null,\"verification\":\"d6850d1c5445547f03c36de9385bca44b2afd22b508b2fc29dc3fb2f8ce24db5\"}"),
        )
    }
}