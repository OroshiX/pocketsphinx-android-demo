/* ====================================================================
 * Copyright (c) 2014 Alpha Cephei Inc.  All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY ALPHA CEPHEI INC. ``AS IS'' AND
 * ANY EXPRESSED OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL CARNEGIE MELLON UNIVERSITY
 * NOR ITS EMPLOYEES BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * ====================================================================
 */
package edu.cmu.pocketsphinx.demo

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import edu.cmu.pocketsphinx.*
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.*

class PocketSphinxActivity : AppCompatActivity(), RecognitionListener {
    private var recognizer: SpeechRecognizer? = null
    private lateinit var captions: HashMap<String, Int>
    private lateinit var captionText: TextView
    private lateinit var resultText: TextView

    @SuppressLint("SetTextI18n")
    public override fun onCreate(state: Bundle?) {
        super.onCreate(state)

        // Prepare the data for UI
        captions = HashMap()
        captions[KWS_SEARCH] = R.string.kws_caption
        captions[MENU_SEARCH] = R.string.menu_caption
        captions[DIGITS_SEARCH] = R.string.digits_caption
        captions[PHONE_SEARCH] = R.string.phone_caption
        captions[FORECAST_SEARCH] = R.string.forecast_caption
        setContentView(R.layout.main)
        captionText = findViewById(R.id.caption_text)
        resultText = findViewById(R.id.result_text)
        captionText.text = "Preparing the recognizer"

        // Check if user has given permission to record audio
        val permissionCheck = ContextCompat.checkSelfPermission(applicationContext,
                Manifest.permission.RECORD_AUDIO)
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO),
                    PERMISSIONS_REQUEST_RECORD_AUDIO)
            return
        }
        // Recognizer initialization is a time-consuming and it involves IO,
        // so we execute it in a coroutine
        lifecycleScope.launch(IO) {
            setupIntensive()
        }
    }

    @SuppressLint("SetTextI18n")
    private suspend fun setupIntensive() {
        withContext(IO) {
            try {

                val assets = Assets(this@PocketSphinxActivity)
                val assetsDir = assets.syncAssets()
                setupRecognizer(assetsDir)
                withContext(Main) {
                    switchSearch(KWS_SEARCH)
                }
            } catch (e: IOException) {
                withContext(Main) {
                    captionText.text = "Failed to init recognizer $e"
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Recognizer initialization is a time-consuming and it involves IO,
                // so we execute it in a coroutine
                lifecycleScope.launch(IO) {
                    setupIntensive()
                }
            } else {
                finish()
            }
        }
    }

    public override fun onDestroy() {
        super.onDestroy()
        recognizer?.apply {
            cancel()
            shutdown()
        }
    }

    /**
     * In partial result we get quick updates about current hypothesis. In
     * keyword spotting mode we can react here, in other modes we need to wait
     * for final result in onResult.
     */
    override fun onPartialResult(hypothesis: Hypothesis?) {
        if (hypothesis == null) return
        when (val text = hypothesis.hypstr) {
            KEYPHRASE -> switchSearch(MENU_SEARCH)
            DIGITS_SEARCH -> switchSearch(DIGITS_SEARCH)
            PHONE_SEARCH -> switchSearch(PHONE_SEARCH)
            FORECAST_SEARCH -> switchSearch(FORECAST_SEARCH)
            else            -> resultText.text = text
        }
    }

    /**
     * This callback is called when we stop the recognizer.
     */
    override fun onResult(hypothesis: Hypothesis?) {
        lifecycleScope.launch(IO) {
            delay(6000)
            withContext(Main) {
                resultText.text = ""
            }
        }
        if (hypothesis != null) {
            val text = hypothesis.hypstr
            Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onBeginningOfSpeech() {
        Log.d(TAG, "You started speaking")
    }

    /**
     * We stop recognizer here to get a final result
     */
    override fun onEndOfSpeech() {
        Log.d(TAG, "You stopped speaking")
        if (recognizer?.searchName != KWS_SEARCH) switchSearch(KWS_SEARCH)
    }

    private fun switchSearch(searchName: String) {
        recognizer?.stop()

        // If we are not spotting, start listening with timeout (10000 ms or 10 seconds).
        if (searchName == KWS_SEARCH) recognizer?.startListening(
                searchName) else recognizer?.startListening(searchName, 10000)
        val caption = resources.getString(captions[searchName]!!)
        captionText.text = caption
    }

    @Throws(IOException::class)
    private fun setupRecognizer(assetsDir: File) {
        // The recognizer can be configured to perform multiple searches
        // of different kind and switch between them
        val recognizer = SpeechRecognizerSetup.defaultSetup()
                .setAcousticModel(File(assetsDir, "en-us-ptm"))
                .setDictionary(File(assetsDir, "cmudict-en-us.dict"))
                .setRawLogDir(
                        assetsDir) // To disable logging of raw audio comment out this call (takes a lot of space on the device)
                .recognizer
        recognizer.addListener(this)

        /* In your application you might not need to add all those searches.
          They are added here for demonstration. You can leave just one.
         */

        // Create keyword-activation search.
        recognizer.addKeyphraseSearch(KWS_SEARCH, KEYPHRASE)

        // Create grammar-based search for selection between demos
        val menuGrammar = File(assetsDir, "menu.gram")
        recognizer.addGrammarSearch(MENU_SEARCH, menuGrammar)

        // Create grammar-based search for digit recognition
        val digitsGrammar = File(assetsDir, "digits.gram")
        recognizer.addGrammarSearch(DIGITS_SEARCH, digitsGrammar)

        // Create language model search
        val languageModel = File(assetsDir, "weather.dmp")
        recognizer.addNgramSearch(FORECAST_SEARCH, languageModel)

        // Phonetic search
        val phoneticModel = File(assetsDir, "en-phone.dmp")
        recognizer.addAllphoneSearch(PHONE_SEARCH, phoneticModel)

        this.recognizer = recognizer
    }

    override fun onError(error: Exception) {
        captionText.text = error.message
    }

    override fun onTimeout() {
        switchSearch(KWS_SEARCH)
    }

    companion object {
        /* Named searches allow to quickly reconfigure the decoder */
        private const val KWS_SEARCH = "wakeup"
        private const val FORECAST_SEARCH = "forecast"
        private const val DIGITS_SEARCH = "digits"
        private const val PHONE_SEARCH = "phones"
        private const val MENU_SEARCH = "menu"

        /* Keyword we are looking for to activate menu */
        private const val KEYPHRASE = "oh mighty computer"

        /* Used to handle permission request */
        private const val PERMISSIONS_REQUEST_RECORD_AUDIO = 1

        private const val TAG = "PocketSphinxActivity"
    }
}