package app.etv.launcher

import android.app.Activity
import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/*
 * Customise → Athan, in the order you'd set it up:
 *  - the athan on or off, and today's times at a glance (they change as you change the options);
 *  - where the times come from: the city, the source, the calculation;
 *  - the athan: the recordings (each one can be heard before you choose it), the dua, listening;
 *  - each prayer: its athan, its recording, moving it or fixing its time, without leaving the panel;
 *  - while the athan plays, and what the home screen shows.
 * When the athan is off, only what still matters (the times on the home screen) is shown.
 */

internal fun SettingsActivity.athanPane() {
    val p = Prefs(this)
    val place = p.place

    row(
        "athan", "Athan at prayer times",
        detail = if (p.athanOn) "The athan at each prayer, then the dua; what's playing pauses"
        else "Off. The times still show on the home screen.",
        toggle = p.athanOn,
    ) {
        p.athanOn = !p.athanOn
        AthanScheduler.schedule(this)
        if (p.athanOn) AthanSounds.ensureChosen(this)
    }
    todayCard(p)

    group("Prayer times")
    row("location", "City", place?.short ?: "Choose your city", opens = true, rerender = false) { pickLocation() }
    val source = when (p.prayerSource) {
        PrayerStore.SRC_MOSQUE -> p.mosqueName ?: "A mosque: choose one"
        PrayerStore.SRC_DEVICE -> "Worked out on the TV"
        else -> "aladhan.com"
    }
    row("source", "Times from", source, opens = true, rerender = false) { pickSource() }
    if (p.prayerSource != PrayerStore.SRC_MOSQUE) {
        row("method", "Calculation method", Methods.byId(p.athanMethod).name, opens = true, rerender = false) { pickMethod() }
        row(
            "asr", "Asr", if (p.asrHanafi) "Hanafi" else "Shafi'i, Maliki, Hanbali",
            detail = if (p.asrHanafi) "The later Asr time" else "The earlier Asr time; OK for Hanafi",
        ) {
            p.asrHanafi = !p.asrHanafi
            timesChanged()
        }
        row(
            "highlat", "Fajr and Isha in summer", Methods.highLatNames[p.highLat.coerceIn(0, 2)],
            detail = "Far north the twilight can last all night; this decides how they're worked out then",
        ) {
            p.highLat = (p.highLat + 1) % 3
            timesChanged()
        }
    }
    note(statusLine(p))

    if (p.athanOn) {
        group("The athan")
        row(
            "fajrsound", "Fajr athan", soundValue(p.athanSound(Prayer.FAJR)),
            detail = "Hear each one before you choose", opens = true, rerender = false,
        ) { pickSound(listOf(Prayer.FAJR)) }
        val others = Prayer.withAthan - Prayer.FAJR
        val otherIds = others.map { p.athanSound(it) }.distinct()
        row(
            "othersound", "Athan for the other prayers",
            if (otherIds.size == 1) soundValue(otherIds[0]) else "Different for each",
            detail = "Dhuhr, Asr, Maghrib and Isha", opens = true, rerender = false,
        ) { pickSound(others) }
        row(
            "dua", "After the athan", if (p.dua.isEmpty()) "No dua" else soundValue(p.dua),
            detail = if (p.dua.isEmpty()) "Nothing after the athan" else "The dua after the athan", opens = true, rerender = false,
        ) { pickDua() }
        val missing = AthanSounds.missing(this)
        if (missing.isNotEmpty()) {
            val busy = missing.any { AthanSounds.isDownloading(it) }
            row(
                "download", "Recordings to fetch", if (busy) "Downloading…" else "${missing.size} not on the TV yet",
                detail = "Until then Makkah's athan plays. Fetched once from the Internet Archive; OK to fetch now",
            ) {
                AthanSounds.ensureChosen(this, force = true)
            }
        }
        row(
            "test", "Listen now", detail = "The next athan and the dua in full, just as at prayer time", opens = true, rerender = false,
        ) {
            val next = PrayerStore.next(this, p, System.currentTimeMillis(), athanOnly = false)?.prayer ?: Prayer.DHUHR
            if (!AthanService.start(this, next, test = true)) toast("Couldn't play the athan")
        }
    }

    group("Each prayer")
    val tz = Days.tz(place)
    val today = Days.today(tz)
    val times = PrayerStore.times(this, p, today)
    val friday = Days.isFriday(today, tz)
    for (pr in Prayer.withAthan) {
        row(
            "prayer_${pr.key}", "${pr.enName(friday)}   ${pr.arName(friday)}",
            times?.let { Days.clock(it[pr.ordinal], p.clock24) } ?: "",
            detail = prayerSummary(p, pr), opens = true, rerender = false,
        ) { editPrayer(pr) }
    }

    if (p.athanOn) {
        group("While the athan plays")
        val pauseValue = when {
            !p.athanPause -> "Keep playing"
            p.athanResume -> "Pause, then carry on"
            else -> "Pause"
        }
        row(
            "pause", "What's playing on the TV", pauseValue,
            detail = when {
                !p.athanPause -> "The athan plays over it"
                p.athanResume -> "It carries on by itself after the dua"
                else -> "It stays paused afterwards"
            },
        ) {
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
            row("pauseaccess", "Pause every app reliably", "Needs one permission", opens = true, rerender = false) { explainNowPlayingAccess() }
        }
        row(
            "volume", "Athan volume", if (p.athanVolume <= 0) "As the TV is" else "${p.athanVolume}%",
            detail = if (p.athanVolume <= 0) "OK to set a level; it un-mutes the TV" else "Un-mutes the TV, and puts the volume back afterwards",
            opens = true, rerender = false,
        ) { pickVolume() }
        row(
            "code", "Code to end the athan early", if (AthanService.hasOwnCode(this)) "Your own" else "Built in",
            detail = "Typed on the remote's number keys during the athan", opens = true, rerender = false,
        ) { changeCode() }
    }

    group("On the home screen")
    row("prayers", "Prayer times", toggle = p.showPrayers) { p.showPrayers = !p.showPrayers }
    if (p.showPrayers) {
        row("sunrise", "Sunrise among them", toggle = p.showSunrise) { p.showSunrise = !p.showSunrise }
        val lanternNames = listOf("A line through the day", "The sun's path", "One at a time", "At the foot")
        row(
            "lanternprayers", "In the Lantern theme", lanternNames[p.lanternPrayers.coerceIn(0, 3)], opens = true, rerender = false,
        ) {
            menu("Prayer times in Lantern", lanternNames, current = p.lanternPrayers.coerceIn(0, 3)) { i ->
                p.lanternPrayers = i
                focusKey = "lanternprayers"
                render()
            }
        }
    }
    row("hijri", "Hijri date, under the date", toggle = p.showHijri) { p.showHijri = !p.showHijri }
    if (p.showHijri) {
        val adjust = listOf(0, 1, 2, -2, -1)
        val names = mapOf(0 to "None", 1 to "+1 day", 2 to "+2 days", -1 to "−1 day", -2 to "−2 days")
        row(
            "hijriadj", "Hijri date adjustment", names[p.hijriAdjust] ?: "None",
            detail = "For when the month starts a day earlier or later where you are",
        ) {
            val i = adjust.indexOf(p.hijriAdjust).coerceAtLeast(0)
            p.hijriAdjust = adjust[(i + 1) % adjust.size]
        }
    }
}

// ---------------- today at a glance ----------------

/**
 * Today's times in a quiet frame under the switch, the next one lit with how long until it, so
 * every change to the city, method or a prayer shows straight away.
 */
private fun SettingsActivity.todayCard(p: Prefs) {
    val place = p.place ?: return
    val tz = Days.tz(place)
    val today = Days.today(tz)
    val t = PrayerStore.times(this, p, today) ?: return
    val friday = Days.isFriday(today, tz)
    val now = System.currentTimeMillis()
    val next = PrayerStore.next(this, p, now, athanOnly = false)
    val card = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dpi(10f), dpi(12f), dpi(10f), dpi(12f))
        background = GradientDrawable().apply {
            cornerRadius = dp(14f)
            setStroke(dpi(1f), pal.tileStroke)
        }
    }
    for (pr in Prayer.values()) {
        val isNext = next != null && next.day == today && next.prayer == pr
        val muted = pr == Prayer.SUNRISE
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        cell.addView(TextView(this).apply {
            text = pr.arName(friday)
            typeface = Fonts.display(this@todayCard, 500)
            sp(13f)
            setTextColor(if (isNext) accent else if (muted) pal.muted else pal.label)
            gravity = Gravity.CENTER
        })
        cell.addView(TextView(this).apply {
            text = Days.clock(t[pr.ordinal], p.clock24)
            typeface = Fonts.body(this@todayCard, if (isNext) 500 else 400)
            sp(16f)
            setTextColor(if (isNext) accent else if (muted) pal.muted else pal.text)
            gravity = Gravity.CENTER
        })
        cell.addView(TextView(this).apply {
            text = if (isNext) inTime(next!!.at - now) else pr.enName(friday)
            typeface = Fonts.body(this@todayCard, 300)
            sp(11.5f)
            setTextColor(if (isNext) accent else pal.muted)
            gravity = Gravity.CENTER
        })
        card.addView(cell, LinearLayout.LayoutParams(0, -2, 1f))
    }
    pane.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dpi(10f)
        marginStart = dpi(4f)
        marginEnd = dpi(4f)
    })
    if (next != null && next.day != today) {
        note("Next: ${next.prayer.enName(next.friday)} tomorrow at ${Days.clock(next.minutes, p.clock24)}")
    }
}

/** "in 1 h 42 min", "in 42 min". */
private fun inTime(ms: Long): String {
    val mins = ((ms + 59_999L) / 60_000L).toInt().coerceAtLeast(0)
    val h = mins / 60
    val m = mins % 60
    return when {
        h > 0 && m == 0 -> "in $h h"
        h > 0 -> "in $h h $m min"
        mins <= 1 -> "now"
        else -> "in $m min"
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

/** One prayer's options in a few words, for the line under it. */
private fun SettingsActivity.prayerSummary(p: Prefs, pr: Prayer): String {
    val parts = ArrayList<String>()
    if (p.athanOn) parts.add(if (p.athanFor(pr)) AthanSounds.name(this, p.athanSound(pr)) else "No athan")
    val fixed = p.fixedTime(pr)
    val off = p.offset(pr)
    if (fixed >= 0) parts.add("always at ${Days.clock(fixed, p.clock24)}")
    else if (off != 0) parts.add(offsetText(off))
    if (parts.isEmpty()) parts.add("On time")
    return parts.joinToString("  ·  ")
}

private fun offsetText(m: Int) = when {
    m > 0 -> "$m min later"
    m < 0 -> "${-m} min earlier"
    else -> "On time"
}

/** The times depend on a changed option: fetch them again and move the alarm. */
private fun SettingsActivity.timesChanged() {
    AthanScheduler.schedule(this)
    PrayerSync.refresh(this)
}

// ---------------- the city ----------------

internal fun SettingsActivity.pickLocation() {
    val place = Prefs(this).place
    menu(
        "City for the prayer times",
        listOf("Search for a city…", "Find it from the TV's internet connection"),
        note = if (place != null) "Now: ${place.name}" else "The prayer times are worked out for this city.",
    ) { which ->
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
    work("Searching for \"$q\"…", { Geo.search(q) }) { list ->
        when {
            list == null -> toast("Couldn't search. Is the TV online?")
            list.isEmpty() -> toast("Nothing found for \"$q\"")
            else -> menu("Choose your city", list.map { it.short }, details = list.map { it.name }) { i -> setPlace(list[i]) }
        }
    }
}

private fun SettingsActivity.confirmPlace(pl: Place) {
    menu(
        "Is this right?",
        listOf("Use ${pl.short}", "Search instead"),
        note = "${pl.name}\n\nIt's worked out from the TV's internet connection, so it can be a nearby city.",
    ) { which ->
        if (which == 0) setPlace(pl)
        else askText("Search for a city", "For example Hannover", "", InputType.TYPE_CLASS_TEXT) { q -> if (q.isNotBlank()) searchCity(q) }
    }
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
    menu(
        "Prayer times from",
        listOf("aladhan.com", "A mosque", "On the TV"),
        details = listOf(
            "Worked out online for your city; the default",
            if (p.mosqueName != null) "Now: ${p.mosqueName}. The times it publishes on mawaqit.net" else "The times a mosque publishes on mawaqit.net",
            "Worked out here, no internet needed",
        ),
        current = p.prayerSource.coerceIn(0, 2),
    ) { which ->
        focusKey = "source"
        when (which) {
            PrayerStore.SRC_MOSQUE -> pickMosque()
            else -> {
                p.prayerSource = which
                afterSourceChange()
            }
        }
    }
}

private fun SettingsActivity.pickMosque() {
    val place = Prefs(this).place
    val items = ArrayList<String>()
    if (place != null) items.add("Mosques near ${place.short}")
    items.add("Search by name or town…")
    menu("Mosque", items) { which ->
        if (place != null && which == 0) {
            work("Looking for mosques near ${place.short}…", { Mawaqit.search(null, place) }) { showMosques(it) }
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
        else -> menu("Choose the mosque", list.map { it.name }, details = list.map { it.where }) { i -> setMosque(list[i]) }
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
    menu(
        "Calculation method",
        list.map { it.name },
        note = "How the times are worked out from the sun. Choose the one used where you are, or by your mosque.",
        current = list.indexOfFirst { it.id == p.athanMethod }.coerceAtLeast(0),
    ) { which ->
        p.athanMethod = list[which].id
        focusKey = "method"
        render()
        timesChanged()
    }
}

private fun SettingsActivity.pickVolume() {
    val p = Prefs(this)
    val volumes = listOf(-1, 25, 40, 55, 70, 85, 100)
    menu(
        "Athan volume",
        volumes.map { if (it <= 0) "As the TV is" else "$it%" },
        note = "A set level un-mutes the TV for the athan and puts its volume back afterwards. The volume keys still work.",
        current = volumes.indexOf(p.athanVolume).let { if (it < 0) 0 else it },
    ) { which ->
        p.athanVolume = volumes[which]
        focusKey = "volume"
        render()
    }
}

// ---------------- recordings ----------------

/** What to say under a recording in the list. */
private fun SettingsActivity.soundDetail(s: AthanSounds.Sound, forFajr: Boolean): String {
    val where = when {
        s.asset != null -> "Comes with eTV"
        AthanSounds.isReady(this, s) -> "On the TV"
        AthanSounds.isDownloading(s) -> "Downloading…"
        AthanSounds.hasFailed(s) -> "Couldn't download: ${AthanSounds.whyFailed(s)}"
        else -> "Fetched once when you listen or choose it"
    }
    val fajr = if (s.fajr && !forFajr) "Recorded for Fajr  ·  " else ""
    return fajr + where
}

/**
 * A panel of recordings you can listen to: moving onto one plays it, OK chooses it. [extra] adds
 * rows after the recordings ("your own link", "no dua"). [onChosen] gets the chosen id.
 */
private fun SettingsActivity.soundSheet(
    title: String,
    note: String,
    sounds: List<AthanSounds.Sound>,
    currentId: String,
    forFajr: Boolean,
    extra: Sheet.(stopPreview: () -> Unit) -> Unit,
    onChosen: (String) -> Unit,
    then: (() -> Unit)? = null,
) {
    lateinit var sh: Sheet
    val base = HashMap<String, String>()
    val pv = AthanPreview(this) { s, text ->
        sh.setDetail("snd:${s.id}", text.ifEmpty { base[s.id] ?: "" }, if (text.isEmpty()) 0 else sh.accent)
    }
    preview?.stop()
    preview = pv
    sh = sheet(
        title,
        note,
        hint = "OK chooses  ·  Play / Pause  ·  Back closes",
    ) {
        for (s in sounds) {
            val d = soundDetail(s, forFajr)
            base[s.id] = d
            item("snd:${s.id}", s.name, detail = d, checked = s.id == currentId, onFocus = { pv.want(s) }) {
                pv.stop()
                dismiss()
                onChosen(s.id)
            }
        }
        this.extra { pv.stop() }
    }
    sh.onPlayPause = { pv.toggle() }
    sh.onClose = {
        pv.stop()
        if (preview === pv) preview = null
        then?.invoke()
    }
    sh.show()
}

/** Chooses the athan for [prayers] (Fajr alone, the other four together, or any one). */
internal fun SettingsActivity.pickSound(prayers: List<Prayer>, then: (() -> Unit)? = null) {
    val p = Prefs(this)
    val forFajr = prayers == listOf(Prayer.FAJR) || (prayers.size == 1 && p.athanSound(prayers[0]).let { id -> AthanSounds.find(this, id)?.fajr == true })
    // The recordings made for Fajr first when choosing Fajr's, last otherwise.
    val list = ArrayList(AthanSounds.athans.sortedBy { if (it.fajr == forFajr) 0 else 1 })
    AthanSounds.find(this, AthanSounds.CUSTOM)?.let { list.add(it) }
    val title = when {
        prayers == listOf(Prayer.FAJR) -> "Fajr athan"
        prayers.size == 1 -> "Athan for ${prayers[0].en}"
        else -> "Athan for the other prayers"
    }
    val note = when {
        prayers.size == 1 -> "Each one plays as you move onto it."
        else -> "For Dhuhr, Asr, Maghrib and Isha. Each one plays as you move onto it."
    }
    soundSheet(
        title, note, list, p.athanSound(prayers[0]), forFajr,
        extra = { stopPreview ->
            group("Your own")
            item(
                "newlink", if (p.customAthanUrl.isBlank()) "A recording from a link…" else "A different link…",
                detail = "Any MP3 on the internet", opens = true, onFocus = stopPreview,
            ) {
                dismiss()
                askText("Link to an athan recording", "https://…  (an MP3 or similar)", p.customAthanUrl, InputType.TYPE_TEXT_VARIATION_URI) { url ->
                    if (url.startsWith("http")) {
                        p.customAthanUrl = url
                        useSound(prayers, AthanSounds.CUSTOM)
                    } else if (url.isNotEmpty()) {
                        toast("That doesn't look like a link")
                    }
                }
            }
        },
        onChosen = { id -> useSound(prayers, id) },
        then = then,
    )
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

internal fun SettingsActivity.pickDua() {
    val p = Prefs(this)
    focusKey = "dua"
    val list = ArrayList(AthanSounds.duas)
    AthanSounds.find(this, AthanSounds.CUSTOM_DUA)?.let { list.add(it) }
    soundSheet(
        "After the athan",
        "Each one plays as you move onto it.",
        list, p.dua, forFajr = false,
        extra = { stopPreview ->
            group("Or")
            item(
                "newlink", if (p.customDuaUrl.isBlank()) "A recording from a link…" else "A different link…",
                detail = "Any MP3 on the internet", opens = true, onFocus = stopPreview,
            ) {
                dismiss()
                askText("Link to a dua recording", "https://…  (an MP3 or similar)", p.customDuaUrl, InputType.TYPE_TEXT_VARIATION_URI) { url ->
                    if (url.startsWith("http")) {
                        p.customDuaUrl = url
                        useDua(AthanSounds.CUSTOM_DUA)
                    } else if (url.isNotEmpty()) {
                        toast("That doesn't look like a link")
                    }
                }
            }
            item("nodua", "No dua", detail = "The athan screen closes after the athan", checked = p.dua.isEmpty(), onFocus = stopPreview) {
                dismiss()
                p.dua = ""
                render()
            }
        },
        onChosen = { id -> useDua(id) },
    )
}

private fun SettingsActivity.useDua(id: String) {
    val p = Prefs(this)
    p.dua = id
    render()
    val s = AthanSounds.find(this, id) ?: return
    if (!AthanSounds.isReady(this, s)) {
        AthanSounds.ensure(this, s, force = true) { ok ->
            toast(if (ok) "The dua is ready" else "Couldn't download the dua: ${AthanSounds.whyFailed(s)}")
        }
    }
}

// ---------------- the code ----------------

private const val NUMBER_SECRET = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD

/** Changes the code: the current one first (built in or your own), then the new one twice. */
private fun SettingsActivity.changeCode() {
    focusKey = "code"
    askText("Your current code", "", "", NUMBER_SECRET) { old ->
        if (old.isEmpty()) return@askText
        checkCode(old) { ok -> if (ok) askNewCode() else toast("That's not the code") }
    }
}

/** The check is slow on purpose: off the main thread. */
private fun SettingsActivity.checkCode(digits: String, then: (Boolean) -> Unit) {
    val app = applicationContext
    Thread {
        val ok = AthanService.isCode(app, digits)
        runOnUiThread { if (!isFinishing) then(ok) }
    }.start()
}

private fun SettingsActivity.askNewCode() {
    askText("New code", "4 to 12 digits; leave it empty for the built-in one", "", NUMBER_SECRET) { first ->
        if (first.isEmpty()) {
            AthanService.setCode(this, "")
            render()
            toast("Back to the built-in code")
            return@askText
        }
        if (first.length !in 4..12 || first.any { it !in '0'..'9' }) {
            toast("Use 4 to 12 digits")
            return@askText
        }
        askText("The new code again", "", "", NUMBER_SECRET) { second ->
            if (second == first) {
                Thread {
                    AthanService.setCode(applicationContext, first)
                    runOnUiThread {
                        render()
                        toast("Code changed. Type it on the remote's number keys to end an athan early.")
                    }
                }.start()
            } else {
                toast("The two didn't match; the code wasn't changed")
            }
        }
    }
}

// ---------------- one prayer ----------------

/**
 * One prayer's options in a panel that stays open while you change them: its athan on or off,
 * its recording, and its time moved with Left / Right (a minute at a time) or fixed (five minutes
 * at a time). The time it will be today is written at the top and follows every change.
 */
internal fun SettingsActivity.editPrayer(pr: Prayer) {
    val p = Prefs(this)
    focusKey = "prayer_${pr.key}"
    val place = p.place
    val tz = Days.tz(place)
    val today = Days.today(tz)
    val friday = Days.isFriday(today, tz)
    fun todayAt(): String = PrayerStore.times(this, p, today)?.let { "Today at ${Days.clock(it[pr.ordinal], p.clock24)}" } ?: ""
    fun changed() {
        AthanScheduler.schedule(this)
        render()
    }
    lateinit var sh: Sheet
    sh = sheet("${pr.enName(friday)}   ${pr.arName(friday)}", todayAt(), hint = "OK changes  ·  ‹ › moves the time  ·  Back closes") {
        note = todayAt()
        if (p.athanOn) {
            item("on", "Athan", detail = if (p.athanFor(pr)) "Plays at this prayer" else "Off; the time still shows", toggle = p.athanFor(pr)) {
                p.setAthanFor(pr, !p.athanFor(pr))
                changed()
                sh.refresh()
            }
            if (p.athanFor(pr)) {
                item("sound", "Recording", AthanSounds.name(this@editPrayer, p.athanSound(pr)), detail = "Hear each one before you choose", opens = true) {
                    pickSound(listOf(pr)) { sh.refresh() }
                }
            }
        }
        val fixed = p.fixedTime(pr)
        if (fixed < 0) {
            val off = p.offset(pr)
            item(
                "offset", "Move the time", "‹  ${offsetText(off)}  ›",
                detail = if (off == 0) "‹ › a minute at a time" else "‹ › a minute  ·  OK puts it back",
                onStep = { d ->
                    p.setOffset(pr, (p.offset(pr) + d).coerceIn(-60, 60))
                    changed()
                    sh.refresh()
                },
            ) {
                if (p.offset(pr) != 0) {
                    p.setOffset(pr, 0)
                    changed()
                    sh.refresh()
                }
            }
        }
        fun startFixed(): Int {
            val now = PrayerStore.times(this@editPrayer, p, today)?.get(pr.ordinal) ?: (13 * 60)
            return (now + 4) / 5 * 5 % (24 * 60)
        }
        item(
            "fixed", "Fixed time", if (fixed >= 0) "‹  ${Days.clock(fixed, p.clock24)}  ›" else "",
            detail = if (fixed >= 0) "Every day  ·  ‹ › five minutes" else "The same time every day",
            toggle = fixed >= 0,
            onStep = { d ->
                val cur = p.fixedTime(pr).let { if (it < 0) startFixed() - 5 * d else it }
                p.setFixedTime(pr, ((cur + 5 * d) % (24 * 60) + 24 * 60) % (24 * 60))
                changed()
                sh.refresh()
            },
        ) {
            p.setFixedTime(pr, if (p.fixedTime(pr) >= 0) -1 else startFixed())
            changed()
            sh.refresh()
        }
        if (p.athanOn && p.athanFor(pr)) {
            group("Listen")
            item("listen", "Play this athan now", detail = "In full, with the dua, just as at prayer time", opens = true) {
                if (!AthanService.start(this@editPrayer, pr, test = true)) toast("Couldn't play the athan")
            }
        }
    }
    sh.onClose = { render() }
    sh.show()
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
 * Does [job] off the main thread with a "please wait" panel, then [then] with its result
 * (null when it failed). Closing the panel drops the result.
 */
private fun <T> SettingsActivity.work(message: String, job: () -> T, then: (T?) -> Unit) {
    var cancelled = false
    val d = sheet(message, hint = "Back cancels") {
        item("cancel", "Cancel") { dismiss() }
    }
    d.onClose = { cancelled = true }
    d.show()
    Thread {
        val result = try {
            job()
        } catch (e: Exception) {
            null
        }
        runOnUiThread {
            if (isFinishing || cancelled) return@runOnUiThread
            d.onClose = null
            try { d.dismiss() } catch (_: Exception) {}
            then(result)
        }
    }.start()
}
