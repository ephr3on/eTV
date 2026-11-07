package app.etv.launcher

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Cleans real downloaded recordings (the workflow puts them in sounds.in), for ffprobe to check. */
class CleanAudioTest {
    @Test
    fun cleansRealRecordings() {
        val dir = File(System.getProperty("sounds.in") ?: return)
        val out = File(System.getProperty("shots.dir") ?: "build/shots").apply { mkdirs() }
        for (f in dir.listFiles().orEmpty().filter { it.name.endsWith(".mp3") }) {
            val to = File(out, "clean-${f.name}")
            assertTrue("no audio found in ${f.name}", AthanSounds.cleanAudio(f, to))
        }
    }
}
