/*******************************************************************************
 *                                                                             *
 *  Copyright (C) 2017 by Max Lv <max.c.lv@gmail.com>                          *
 *  Copyright (C) 2017 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
 *                                                                             *
 *  This program is free software: you can redistribute it and/or modify       *
 *  it under the terms of the GNU General Public License as published by       *
 *  the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                        *
 *                                                                             *
 *  This program is distributed in the hope that it will be useful,            *
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 *  GNU General Public License for more details.                               *
 *                                                                             *
 *  You should have received a copy of the GNU General Public License          *
 *  along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                             *
 *******************************************************************************/

package com.github.shadowsocks

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ShortcutManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.getSystemService
import androidx.lifecycle.lifecycleScope
import androidx.work.await
import com.github.shadowsocks.Core.app
import com.github.shadowsocks.database.Profile
import com.github.shadowsocks.database.ProfileManager
import com.github.shadowsocks.utils.datas
import com.github.shadowsocks.utils.forEachTry
import com.github.shadowsocks.utils.readableMessage
import com.github.shadowsocks.widget.ListHolderListener
import com.google.mlkit.vision.barcode.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import timber.log.Timber

class ScannerActivity : AppCompatActivity(), ImageAnalysis.Analyzer {
    companion object {
        private const val REQUEST_IMPORT = 2
        private const val REQUEST_IMPORT_OR_FINISH = 3
    }

    private val scanner = BarcodeScanning.getClient(BarcodeScannerOptions.Builder().apply {
        setBarcodeFormats(Barcode.FORMAT_QR_CODE)
    }.build())
    private val imageAnalysis by lazy {
        ImageAnalysis.Builder().apply {
            setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        }.build().also { it.setAnalyzer(Dispatchers.Main.immediate.asExecutor(), this) }
    }

    @ExperimentalGetImage
    override fun analyze(image: ImageProxy) {
        val mediaImage = image.image ?: return
        lifecycleScope.launchWhenCreated {
            val result = try {
                process { InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees) }.also {
                    if (it) imageAnalysis.clearAnalyzer()
                }
            } catch (e: Exception) {
                return@launchWhenCreated Timber.w(e)
            } finally {
                image.close()
            }
            if (result) onSupportNavigateUp()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < 23) return startImport()    // we show no love to lollipop
        if (Build.VERSION.SDK_INT >= 25) getSystemService<ShortcutManager>()!!.reportShortcutUsed("scan")
        if (try {
                    getSystemService<CameraManager>()?.cameraIdList?.isEmpty()
                } catch (_: CameraAccessException) {
                    true
                } != false) return startImport()
        setContentView(R.layout.layout_scanner)
        ListHolderListener.setup(this)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        requestCamera.launch(Manifest.permission.CAMERA)
    }
    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) lifecycleScope.launchWhenCreated {
            val cameraProvider = ProcessCameraProvider.getInstance(this@ScannerActivity).await()
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(findViewById<PreviewView>(R.id.barcode).createSurfaceProvider())
            cameraProvider.bindToLifecycle(this@ScannerActivity, CameraSelector.Builder().apply {
                requireLensFacing(CameraSelector.LENS_FACING_BACK)
            }.build(), preview, imageAnalysis)
        } else permissionMissing()
    }

    private suspend inline fun process(feature: Profile? = Core.currentProfile?.main,
                                       crossinline image: () -> InputImage): Boolean {
        val barcodes = withContext(Dispatchers.Default) { scanner.process(image()).await() }
        var result = false
        for (profile in Profile.findAllUrls(barcodes.mapNotNull { it.rawValue }.joinToString("\n"), feature)) {
            ProfileManager.createProfile(profile)
            result = true
        }
        return result
    }

    private fun permissionMissing() {
        Toast.makeText(this, R.string.add_profile_scanner_permission_required, Toast.LENGTH_SHORT).show()
        startImport()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.scanner_menu, menu)
        return true
    }
    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.action_import_clipboard -> {
            startImport(true)
            true
        }
        else -> false
    }

    /**
     * See also: https://stackoverflow.com/a/31350642/2245107
     */
    override fun shouldUpRecreateTask(targetIntent: Intent?) = super.shouldUpRecreateTask(targetIntent) || isTaskRoot

    private var finished = false
    override fun onSupportNavigateUp(): Boolean {
        if (finished) return false
        finished = true
        return super.onSupportNavigateUp()
    }

    private fun startImport(manual: Boolean = false) = startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "image/*"
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
    }, if (manual) REQUEST_IMPORT else REQUEST_IMPORT_OR_FINISH)
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQUEST_IMPORT, REQUEST_IMPORT_OR_FINISH -> when {
                resultCode == Activity.RESULT_OK -> GlobalScope.launch(Dispatchers.Main.immediate) {
                    onSupportNavigateUp()
                    val feature = Core.currentProfile?.main
                    try {
                        var success = false
                        data!!.datas.forEachTry { uri ->
                            if (process(feature) { InputImage.fromFilePath(app, uri) }) success = true
                        }
                        Toast.makeText(app, if (success) R.string.action_import_msg else R.string.action_import_err,
                                Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(app, e.readableMessage, Toast.LENGTH_LONG).show()
                    }
                }
                requestCode == REQUEST_IMPORT_OR_FINISH -> onSupportNavigateUp()
            }
            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }
}
