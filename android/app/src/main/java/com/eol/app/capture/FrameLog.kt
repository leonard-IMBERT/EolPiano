package com.eol.app.capture

import android.util.Log
import java.io.File
import java.io.OutputStream

class FrameLog(fps: Int, durationSec: Int) {

    companion object {
        // Schema is (sensorTs, arrivalTs, resultTs, exposureNs, frameDurationNs)
        const val DATA_SIZE = 5
    }

    val nEntries = fps * durationSec * 2
    private val data: LongArray =
        // Times 2 for image log and result log
        LongArray(size = nEntries * DATA_SIZE)

    private var idx = 0

    fun pushImageLog(sensorTs: Long, arrivalTs: Long) {
        if (idx < nEntries) {
            data[idx * DATA_SIZE] = sensorTs
            data[idx * DATA_SIZE + 1] = arrivalTs
        }
        idx++
    }

    fun pushResultLog(sensorTs: Long, resultTs: Long, exposureNs: Long, frameDurationNs: Long) {
        if (idx < nEntries) {
            data[idx * DATA_SIZE] = sensorTs
            data[idx * DATA_SIZE + 2] = resultTs
            data[idx * DATA_SIZE + 3] = exposureNs
            data[idx * DATA_SIZE + 4] = frameDurationNs
        }
        idx++
    }

    fun writeCsv(file: File) {
        file.bufferedWriter().use { w ->
            w.write("sensorTs,arrivalTs,resultTs,exposureNs,frameDurationNs")
            w.newLine()
            for (idx in 0 until minOf(idx, nEntries)) {
                val frame = idx * DATA_SIZE
                w.write("${data[frame]},${data[frame + 1]},${data[frame + 2]},${data[frame + 3]},${data[frame + 4]}")
                w.newLine()
            }
            w.flush()
        }
        Log.i("Eol.framelog", "${nEntries - idx} empty rows")
    }
}