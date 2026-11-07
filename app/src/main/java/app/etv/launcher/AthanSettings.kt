package app.etv.launcher

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast

/*
 * Customise → Athan: where you are, where the times come from, the athan for each prayer, the dua
 * after it, what happens to whatever is playing, and what the home screen shows.
 */

internal fun SettingsActivity.athanPane() {
    val p = Prefs(this)
    val place = p.place

    group("Athan")
    row("athan", "Athan at prayer times", if (p.athanOn) "On" else "Off") {
        p.athanOn = !p.athanOn
        AthanScheduler.schedule(this)
        if (p.athanOn) AthanSounds.ensureChosen(this)
    }
    row("location", "City", place?.name ?: "Choose your city", rerender = false) { pickLocation() }
    val source = when (p.prayerSource) {
        PrayerStore.SRC_MOSQUE -> "Mosque: ${p.mosqueName ?: "choose one"}"
        PrayerStore.SRC_DEVICE -> "Worked out on the TV"
        else -> "aladhan.com"
    }
    row("source", "Prayer times from", source, rerender = false) { pickSource() }
    if (p.prayerSource != PrayerStore.SRC_MOSQUE) {
        row("method", "Calculation method", Methods.byId(p.athanMethod).name, rerender = false) { pickMethod() }
        row("asr", "Asr", if (p.asrHanafi) "Hanafi" else "Shafi'i, Maliki, Hanbali") {
            p.asrHanafi = !p.asrHanafi
            timesChanged()
        }
        row("highlat", "Fajr and Isha in summer", Methods.highLatNames[p.highLat.coerceIn(0, 2)]) {
            p.highLat = (p.highLat + 1) % 3
            timesChanged()
        }
    }
    note(statusLine(p))

    group("Recordings")
    row("fajrsound", "Fajr athan", soundValue(p.athanSound(Prayer.FAJR)), rerender = false) { pickSound(listOf(Prayer.FAJR)) }
    val others = Prayer.withAthan - Prayer.FAJR
    val otherIds = others.map { p.athanSound(it) }.distinct()
    row("othersound", "Athan for the other prayers", if (otherIds.size == 1) soundValue(otherIds[0]) else "Different for each", rerender = false) {
        pickSound(others)
    }
    row("dua", "After the athan", if (p.dua.isEmpty()) "No dua" else "Dua  ·  ${soundValue(p.dua)}", rerender = false) { pickDua() }
    val missing = AthanSounds.missing(this)
    if (missing.isNotEmpty() && p.athanOn) {
        val busy = missing.any { AthanSounds.isDownloading(it) }
        row("download", "Recordings to fetch", if (busy) "Downloading…" else "${missing.size} not on the TV yet  ·  Fetch now") {
            AthanSounds.ensureChosen(this, force = true)
        }
        note("Until a recording is on the TV, Makkah's athan plays instead. They're fetched once from the Internet Archive.")
    }

    group("Each prayer")
    val tz = Days.tz(place)
    val today = Days.today(tz)
    val times = PrayerStore.times(this, p, today)
    val friday = Days.isFriday(today, tz)
    for (pr in Prayer.withAthan) {
        val time = times?.let { "  ·  ${Days.clock(it[pr.ordinal], p.clock24)}" } ?: ""
        row("prayer_${pr.key}", "${pr.arName(friday)}   ${pr.enName(friday)}$time", prayerSummary(p, pr), rerender = false) {
            editPrayer(pr)
        }
    }
    note("Choose a prayer to switch its athan off, give it its own recording, move it a few minutes, or set a fixed time.")

    group("While the athan plays")
    val pauseValue = when {
        !p.athanPause -> "Keep playing"
        p.athanResume -> "Pause, then carry on"
        else -> "Pause"
    }
    row("pause", "What's playing on the TV", pauseValue) {
        when {
            p.athanPause && p.athanResume -> p.athanResume = false
            p.athanPause -> p.athanPause = false
            else -> {
                p.athanPause = true
                p.athanResume = true
            }
        }
    }
    if (p.athanPause && !NowPlayingWatcher.hasAccess(this)) {
        row("pauseaccess", "Pause every app reliably", "Needs one permission", rerender = false) { explainNowPlayingAccess() }
    }
    val volumes = listOf(-1, 25, 40, 55, 70, 85, 100)
    row("volume", "Athan volume", if (p.athanVolume <= 0) "As the TV is" else "${p.athanVolume}%") {
        val i = volumes.indexOf(p.athanVolume)
        p.athanVolume = volumes[(i + 1) % volumes.size]
    }
    row("test", "Listen now", "Play the next athan and the dua", rerender = false) {
        val next = PrayerStore.next(this, p, System.currentTimeMillis(), athanOnly = false)?.prayer ?: Prayer.DHUHR
        if (!AthanService.start(this, next, test = true)) toast("Couldn't play the athan")
    }

    group("On the home screen")
    row("prayers", "Prayer times", if (p.showPrayers) "Shown" else "Hidden") { p.showPrayers = !p.showPrayers }
    row("sunrise", "Sunrise among them", if (p.showSunrise) "Shown" else "Hidden") { p.showSunrise = !p.showSunrise }
    val lanternNames = listOf("A line through the day", "The sun's path", "One at a time", "At the foot")
    row("lanternprayers", "In Lantern", lanternNames[p.lanternPrayers.coerceIn(0, 3)]) {
        p.lanternPrayers = (p.lanternPrayers + 1) % lanternNames.size
    }
    row("hijri", "Hijri date, under the date", if (p.showHijri) "Shown" else "Hidden") { p.showHijri = !p.showHijri }
    if (p.showHijri) {
        val adjust = listOf(0, 1, 2, -2, -1)
        val names = mapOf(0 to "None", 1 to "+1 day", 2 to "+2 days", -1 to "−1 day", -2 to "−2 days")
        row("hijriadj", "Hijri date adjustment", names[p.hijriAdjust] ?: "None") {
            val i = adjust.indexOf(p.hijriAdjust).coerceAtLeast(0)
            p.hijriAdjust = adjust[(i + 1) % adjust.size]
        }
    }
}

private fun SettingsActivity.statusLine(p: Prefs): String {
    val place = p.place ?: return "Choose your city first: the prayer times are then worked out for it, and the athan plays at each one."
    if (p.prayerSource == PrayerStore.SRC_DEVICE) return "Worked out on the TV for ${place.short}, no internet needed."
    if (p.prayerSource == PrayerStore.SRC_MOSQUE && p.mosqueSlug.isNullOrEmpty()) return "Choose a mosque. Until then the TV works the times out itself."
    if (PrayerSync.busy) return "Updating the times…"
    val at = PrayerStore.fetchedAt(this, p)
    val status = PrayerStore.status
    if (at == 0L) return status.ifEmpty { "Not updated from the internet yet; the TV works the times out itself until then." }
    val days = ((System.currentTimeMillis() - at) / 86_400_000L).toInt()
    val ago = when (days) {
        0 -> "today"
        1 -> "yesterday"
        else -> "$days days ago"
    }
    val from = if (p.prayerSource == PrayerStore.SRC_MOSQUE) "${p.mosqueName ?: "the mosque"} (mawaqit.net)" else "aladhan.com for ${place.short}"
    val warn = if (status.startsWith("Couldn't")) "\n$status" else ""
    return "Times from $from, updated $ago. They're kept on the TV, so they work without internet too.$warn"
}

private fun SettingsActivity.soundValue(id: String): String {
    val s = AthanSounds.find(this, id) ?: return AthanSounds.name(this, id)
    val st = AthanSounds.statusOf(this, s)
    return if (st.isEmpty()) s.name else "${s.name}  ($st)"
}

private fun SettingsActivity.prayerSummary(p: Prefs, pr: Prayer): String {
    val parts = ArrayList<String>()
    parts.add(if (p.athanFor(pr)) AthanSounds.name(this, p.athanSound(pr)) else "No athan")
    val fixed = p.fixedTime(pr)
    val off = p.offset(pr)
    if (fixed >= 0) parts.add("always ${Days.clock(fixed, p.clock24)}")
    else if (off != 0) parts.add(offsetText(off))
    return parts.joinToString("  ·  ")
}

private fun offsetText(m: Int) = when {
    m > 0 -> "+$m min"
    m < 0 -> "−${-m} min"
    else -> "On time"
}

/** The times depend on a changed option: fetch them again and move the alarm. */
private fun SettingsActivity.timesChanged() {
    AthanScheduler.schedule(this)
    PrayerSync.refresh(this)
}

// ---------------- the city ----------------

internal fun SettingsActivity.pickLocation() {
    val items = arrayOf("Search for a city…", "Find it from the TV's internet connection")
    choose("City for the prayer times", items) { which ->
        when (which) {
            0 -> askText("Search for a city", "For example Hannover, or القاهرة", "", InputType.TYPE_CLASS_TEXT) { q ->
                if (q.isNotBlank()) searchCity(q)
            }
            1 -> work("Finding where the TV is…", { Geo.fromInternet() }) { found ->
                if (found == null) toast("Couldn't tell where the TV is. Search for your city instead.")
                else confirmPlace(found)
            }
        }
    }
}

private fun SettingsActivity.searchCity(q: String) {
    work("Searching…", { Geo.search(q) }) { list ->
        when {
            list == null -> toast("Couldn't search. Is the TV online?")
            list.isEmpty() -> toast("Nothing found for \"$q\"")
            else -> choose("Choose your city", list.map { it.name }.toTypedArray()) { i -> setPlace(list[i]) }
        }
    }
}

private fun SettingsActivity.confirmPlace(pl: Place) {
    AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
        .setTitle("Is this right?")
        .setMessage("${pl.name}\n\nIt's worked out from the TV's internet connection, so it can be a nearby city.")
        .setPositiveButton("Use it") { _, _ -> setPlace(pl) }
        .setNegativeButton("Search instead") { _, _ ->
            askText("Search for a city", "For example Hannover", "", InputType.TYPE_CLASS_TEXT) { q -> if (q.isNotBlank()) searchCity(q) }
        }
        .show()
}

private fun SettingsActivity.setPlace(pl: Place) {
    val p = Prefs(this)
    p.place = pl
    focusKey = "location"
    afterSourceChange()
}

private fun SettingsActivity.afterSourceChange() {
    PrayerStore.status = ""
    render()
    AthanScheduler.schedule(this)
    AthanSounds.ensureChosen(this)
    PrayerSync.refresh(this) { ok, msg -> if (!ok && msg.isNotEmpty()) toast(msg) }
}

// ---------------- where the times come from ----------------

private fun SettingsActivity.pickSource() {
    val p = Prefs(this)
    val items = arrayOf(
        "aladhan.com  —  worked out online for your city",
        "A mosque  —  the times it publishes on mawaqit.net",
        "On the TV  —  worked out here, no internet needed",
    )
    AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
        .setTitle("Prayer times from")
        .setSingleChoiceItems(items, p.prayerSource.coerceIn(0, 2)) { d, which ->
            d.dismiss()
            focusKey = "source"
            when (which) {
                PrayerStore.SRC_MOSQUE -> pickMosque()
                else -> {
                    p.prayerSource = which
                    afterSourceChange()
                }
            }
        }
        .setNegativeButton("Cancel", null)
        .show()
}

private fun SettingsActivity.pickMosque() {
    val place = Prefs(this).place
    val items = ArrayList<String>()
    if (place != null) items.add("Mosques near ${place.short}")
    items.add("Search for a mosque by name or town…")
    choose("Mosque", items.toTypedArray()) { which ->
        if (place != null && which == 0) {
            work("Looking for mosques…", { Mawaqit.search(null, place) }) { showMosques(it) }
        } else {
            askText("Search for a mosque", "Its name, or the town", "", InputType.TYPE_CLASS_TEXT) { q ->
                if (q.isNotBlank()) work("Searching…", { Mawaqit.search(q, null) }) { showMosques(it) }
            }
        }
    }
}

private fun SettingsActivity.showMosques(list: List<Mawaqit.Mosque>?) {
    when {
        list == null -> toast("mawaqit.net didn't answer. Try again later, or choose aladhan.com.")
        list.isEmpty() -> toast("No mosques found")
        else -> {
            val labels = list.map { m -> if (m.where.isNotBlank()) "${m.name}  —  ${m.where}" else m.name }.toTypedArray()
            choose("Choose the mosque", labels) { i -> setMosque(list[i]) }
        }
    }
}

private fun SettingsActivity.setMosque(m: Mawaqit.Mosque) {
    val p = Prefs(this)
    p.mosqueSlug = m.slug
    p.mosqueName = m.name
    p.prayerSource = PrayerStore.SRC_MOSQUE
    // No city yet: the mosque's own place will do.
    if (p.place == null && m.lat != null && m.lon != null) {
        p.place = Place(m.where.ifBlank { m.name }, m.lat, m.lon, java.util.TimeZone.getDefault().id)
    }
    focusKey = "source"
    PrayerStore.status = ""
    render()
    AthanScheduler.schedule(this)
    PrayerSync.refresh(this) { ok, msg ->
        toast(if (ok) "Times from ${m.name}" else msg)
    }
}

private fun SettingsActivity.pickMethod() {
    val p = Prefs(this)
    val list = Methods.list
    val current = list.indexOfFirst { it.id == p.athanMethod }.coerceAtLeast(0)
    AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
        .setTitle("Calculation method")
        .setSingleChoiceItems(list.map { it.name }.toTypedArray(), current) { d, which ->
            d.dismiss()
            p.athanMethod = list[which].id
            focusKey = "method"
            render()
            timesChanged()
        }
        .setNegativeButton("Cancel", null)
        .show()
}

// ---------------- recordings ----------------

/** Chooses the athan for [prayers] (Fajr alone, or the other four together). */
private fun SettingsActivity.pickSound(prayers: List<Prayer>) {
    val p = Prefs(this)
    val forFajr = prayers == listOf(Prayer.FAJR)
    // The recordings made for Fajr first when choosing Fajr's, last otherwise.
    val list = AthanSounds.athans.sortedBy { if (it.fajr == forFajr) 0 else 1 }
    val labels = list.map { s ->
        val tag = if (s.fajr && !forFajr) "  ·  Fajr's athan" else ""
        val st = AthanSounds.statusOf(this, s)
        s.name + tag + if (st.isEmpty()) "" else "  ($st)"
    } + "Your own recording, from a link…"
    val currentId = p.athanSound(prayers[0])
    val checked = list.indexOfFirst { it.id == currentId }.let { if (it < 0 && currentId == AthanSounds.CUSTOM) list.size else it }
    AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
        .setTitle(if (forFajr) "Fajr athan" else "Athan for Dhuhr, Asr, Maghrib and Isha")
        .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which ->
            d.dismiss()
            if (which == list.size) {
                askText("Link to an athan recording", "https://…  (an MP3 or similar)", p.customAthanUrl, InputType.TYPE_TEXT_VARIATION_URI) { url ->
                    if (url.startsWith("http")) {
                        p.customAthanUrl = url
                        useSound(prayers, AthanSounds.CUSTOM)
                    } else if (url.isNotEmpty()) {
                        toast("That doesn't look like a link")
                    }
                }
            } else {
                useSound(prayers, list[which].id)
            }
        }
        .setNegativeButton("Cancel", null)
        .show()
}

private fun SettingsActivity.useSound(prayers: List<Prayer>, id: String) {
    val p = Prefs(this)
    prayers.forEach { p.setAthanSound(it, id) }
    render()
    val s = AthanSounds.find(this, id) ?: return
    if (!AthanSounds.isReady(this, s)) {
        AthanSounds.ensure(this, s, force = true) { ok ->
            toast(if (ok) "${s.name} is ready" else "Couldn't download ${s.name} (${AthanSounds.whyFailed(s)}). Makkah's athan plays until it works.")
        }
    }
}

private fun SettingsActivity.pickDua() {
    val p = Prefs(this)
    val list = AthanSounds.duas
    val labels = list.map { s ->
        val st = AthanSounds.statusOf(this, s)
        s.name + if (st.isEmpty()) "" else "  ($st)"
    } + listOf("Your own recording, from a link…", "No dua")
    val checked = when (p.dua) {
        "" -> list.size + 1
        AthanSounds.CUSTOM_DUA -> list.size
        else -> list.indexOfFirst { it.id == p.dua }
    }
    AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
        .setTitle("Dua after the athan")
        .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which ->
            d.dismiss()
            focusKey = "dua"
            when {
                which < list.size -> {
                    p.dua = list[which].id
                    render()
                    val s = list[which]
                    if (!AthanSounds.isReady(this, s)) {
                        AthanSounds.ensure(this, s, force = true) { ok ->
                            toast(if (ok) "The dua is ready" else "Couldn't download the dua: ${AthanSounds.whyFailed(s)}")
                        }
                    }
                }
                which == list.size -> askText("Link to a dua recording", "https://…  (an MP3 or similar)", p.customDuaUrl, InputType.TYPE_TEXT_VARIATION_URI) { url ->
                    if (url.startsWith("http")) {
                        p.customDuaUrl = url
                        p.dua = AthanSounds.CUSTOM_DUA
                        render()
                        AthanSounds.find(this, AthanSounds.CUSTOM_DUA)?.let { s ->
                            AthanSounds.ensure(this, s, force = true) { ok -> if (!ok) toast("Couldn't download that recording: ${AthanSounds.whyFailed(s)}") }
                        }
                    }
                }
                else -> {
                    p.dua = ""
                    render()
                }
            }
        }
        .setNegativeButton("Cancel", null)
        .show()
}

// ---------------- one prayer ----------------

private fun SettingsActivity.editPrayer(pr: Prayer) {
    val p = Prefs(this)
    focusKey = "prayer_${pr.key}"
    val fixed = p.fixedTime(pr)
    val items = arrayOf(
        "Athan:  " + if (p.athanFor(pr)) "On" else "Off (the time still shows)",
        "Recording:  " + AthanSounds.name(this, p.athanSound(pr)),
        "Move the time:  " + offsetText(p.offset(pr)),
        "Fixed time:  " + if (fixed >= 0) Days.clock(fixed, p.clock24) else "Off",
        "Listen to it now",
    )
    choose("${pr.ar}  ·  ${pr.en}", items) { which ->
        when (which) {
            0 -> {
                p.setAthanFor(pr, !p.athanFor(pr))
                AthanScheduler.schedule(this)
                render()
                editPrayer(pr)
            }
            1 -> pickSound(listOf(pr))
            2 -> pickOffset(pr)
            3 -> askText(
                "Fixed time for ${pr.en}",
                "For example 13:30. Leave it empty to follow the prayer times.",
                if (fixed >= 0) Days.clock(fixed, true) else "",
                InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME,
            ) { text ->
                if (text.isEmpty()) {
                    p.setFixedTime(pr, -1)
                } else {
                    val m = Days.parseClock(text)
                    if (m == null) toast("Write the time like 13:30") else p.setFixedTime(pr, m)
                }
                AthanScheduler.schedule(this)
                render()
            }
            4 -> if (!AthanService.start(this, pr, test = true)) toast("Couldn't play the athan")
        }
    }
}

private fun SettingsActivity.pickOffset(pr: Prayer) {
    val p = Prefs(this)
    val values = listOf(-30, -20, -15, -10, -5, -4, -3, -2, -1, 0, 1, 2, 3, 4, 5, 10, 15, 20, 30)
    val labels = values.map { offsetText(it) }.toTypedArray()
    AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
        .setTitle("Move ${pr.en} by")
        .setSingleChoiceItems(labels, values.indexOf(p.offset(pr)).coerceAtLeast(0)) { d, which ->
            d.dismiss()
            p.setOffset(pr, values[which])
            AthanScheduler.schedule(this)
            render()
        }
        .setNegativeButton("Cancel", null)
        .show()
}

// ---------------- small helpers ----------------

private fun Activity.toast(text: String) {
    if (!isFinishing) Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}

/** A one-line text box with the TV's keyboard. */
internal fun Activity.askText(title: String, hint: String, initial: String, inputType: Int, onOk: (String) -> Unit) {
    val edit = EditText(this).apply {
        setText(initial)
        setSelection(text.length)
        this.hint = hint
        isSingleLine = true
        this.inputType = inputType or (if (inputType and InputType.TYPE_MASK_CLASS == 0) InputType.TYPE_CLASS_TEXT else 0)
        imeOptions = EditorInfo.IME_ACTION_DONE
    }
    val box = FrameLayout(this).apply {
        setPadding(dpi(24f), dpi(8f), dpi(24f), 0)
        addView(edit)
    }
    var answered = false
    fun answer() {
        if (answered) return
        answered = true
        onOk(edit.text.toString().trim())
    }
    val d = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
        .setTitle(title)
        .setView(box)
        .setPositiveButton("OK") { _, _ -> answer() }
        .setNegativeButton("Cancel", null)
        .create()
    edit.setOnEditorActionListener { _, id, _ ->
        if (id == EditorInfo.IME_ACTION_DONE || id == EditorInfo.IME_ACTION_GO) {
            d.dismiss()
            answer()
            true
        } else {
            false
        }
    }
    d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    d.show()
    edit.requestFocus()
}

/**
 * Does [job] off the main thread with a "please wait" box, then [then] with its result
 * (null when it failed). Cancelling the box drops the result.
 */
private fun <T> Activity.work(message: String, job: () -> T, then: (T?) -> Unit) {
    var cancelled = false
    val d = AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
        .setMessage(message)
        .setNegativeButton("Cancel") { _, _ -> cancelled = true }
        .setOnCancelListener { cancelled = true }
        .show()
    Thread {
        val result = try {
            job()
        } catch (e: Exception) {
            null
        }
        runOnUiThread {
            if (isFinishing || cancelled) return@runOnUiThread
            try { d.dismiss() } catch (_: Exception) {}
            then(result)
        }
    }.start()
}
