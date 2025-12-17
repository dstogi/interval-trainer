package com.dstogi.intervaltrainer

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.ui.platform.LocalContext
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.UUID
import kotlin.random.Random
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.DisposableEffect

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    IntervalTrainerApp()
                }
            }
        }
    }
}


        private sealed interface Screen {
    data object Home : Screen
    data class Editor(val existing: IntervalCard? = null) : Screen
    data class Runner(val card: IntervalCard) : Screen
}

@Composable
private fun IntervalTrainerApp() {
    val context = LocalContext.current
    val store = remember(context) { CardsStore(context) }
    val scope = rememberCoroutineScope()

    // Beim ersten Start Sample-Karte anlegen
    LaunchedEffect(Unit) {
        store.ensureSeeded(sampleCard())
    }

    // Karten aus DataStore laden
    val cards by store.cardsFlow.collectAsState(initial = emptyList())

    var screen: Screen by remember { mutableStateOf(Screen.Home) }

    when (val s = screen) {
        Screen.Home -> HomeScreen(
            cards = cards,
            onAdd = { screen = Screen.Editor(null) },
            onStart = { card -> screen = Screen.Runner(card) },
            onEdit = { card -> screen = Screen.Editor(card) },
            onDuplicate = { card ->
                val copy = card.copy(
                    id = UUID.randomUUID().toString(),
                    title = card.title + " (Copy)"
                )
                scope.launch { store.upsert(copy) }
            },
            onDelete = { card ->
                scope.launch { store.delete(card.id) }
            }
        )

        is Screen.Editor -> EditorScreen(
            initial = s.existing,
            onCancel = { screen = Screen.Home },
            onSave = { saved ->
                scope.launch {
                    store.upsert(saved)
                    screen = Screen.Home
                }
            }
        )

        is Screen.Runner -> RunnerScreen(
            card = s.card,
            onBack = { screen = Screen.Home }
        )
    }
}

private fun sampleCard(): IntervalCard {
    return IntervalCard(
        title = "HIIT Kurz",
        timing = TimingConfig(
            warmupSec = 0,
            workSec = 20,
            restBetweenRepsSec = 0,
            repsPerSet = 1,
            restBetweenSetsSec = 60,
            sets = 4,
            cooldownSec = 0
        ),
        exercise = Exercise(name = "Liegestütze")
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    cards: List<IntervalCard>,
    onAdd: () -> Unit,
    onStart: (IntervalCard) -> Unit,
    onEdit: (IntervalCard) -> Unit,
    onDuplicate: (IntervalCard) -> Unit,
    onDelete: (IntervalCard) -> Unit
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Interval Trainer") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) { Text("+") }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(cards, key = { it.id }) { card ->
                val totalSec = PhaseBuilder.build(card).sumOf { it.durationSec }

                Card {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(card.title, style = MaterialTheme.typography.titleLarge)
                        Text("Übung: ${card.exercise?.name ?: "-"}")
                        Text("${card.timing.sets} Sätze · ${card.timing.repsPerSet} Wdh/Satz · ${formatDuration(card.timing.workSec)} Arbeit")
                        Text("Pause Wdh: ${formatDuration(card.timing.restBetweenRepsSec)} · Satzpause: ${formatDuration(card.timing.restBetweenSetsSec)}")
                        Text("Gesamt: ${formatDuration(totalSec)}")

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onStart(card) }) { Text("Start") }
                            OutlinedButton(onClick = { onEdit(card) }) { Text("Bearbeiten") }
                            OutlinedButton(onClick = { onDuplicate(card) }) { Text("Duplizieren") }
                            OutlinedButton(onClick = { onDelete(card) }) { Text("Löschen") }

                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorScreen(
    initial: IntervalCard?,
    onCancel: () -> Unit,
    onSave: (IntervalCard) -> Unit
) {
    val initialTiming = initial?.timing

    var title by remember { mutableStateOf(initial?.title ?: "") }
    var exerciseName by remember { mutableStateOf(initial?.exercise?.name ?: "") }
    var exerciseNotes by remember { mutableStateOf(initial?.exercise?.notes ?: "") }

    var warmupText by remember { mutableStateOf(formatDuration(initialTiming?.warmupSec ?: 0)) }
    var workText by remember { mutableStateOf(formatDuration(initialTiming?.workSec ?: 20)) }
    var restRepsText by remember { mutableStateOf(formatDuration(initialTiming?.restBetweenRepsSec ?: 0)) }
    var repsText by remember { mutableStateOf((initialTiming?.repsPerSet ?: 1).toString()) }
    var restSetsText by remember { mutableStateOf(formatDuration(initialTiming?.restBetweenSetsSec ?: 60)) }
    var setsText by remember { mutableStateOf((initialTiming?.sets ?: 4).toString()) }
    var cooldownText by remember { mutableStateOf(formatDuration(initialTiming?.cooldownSec ?: 0)) }

    var error by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()

    val randomExercises = remember {
        listOf("Liegestütze", "Kniebeugen", "Ausfallschritte", "Plank", "Mountain Climbers", "Jumping Jacks", "Burpees")
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(if (initial == null) "Neue Karte" else "Karte bearbeiten") }) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (error != null) Text(error!!, color = MaterialTheme.colorScheme.error)

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Titel") },
                modifier = Modifier.fillMaxWidth()
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = exerciseName,
                    onValueChange = { exerciseName = it },
                    label = { Text("Übung (z.B. Liegestütze)") },
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = { exerciseName = randomExercises[Random.nextInt(randomExercises.size)] }) {
                    Text("🎲")
                }
            }

            OutlinedTextField(
                value = exerciseNotes,
                onValueChange = { exerciseNotes = it },
                label = { Text("Notizen (optional)") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )

            Divider()
            Text("Zeiten (mm:ss oder Sekunden)", style = MaterialTheme.typography.titleMedium)

            DurationField("Warmup", warmupText) { warmupText = it }
            DurationField("Arbeit", workText) { workText = it }
            DurationField("Pause zwischen Wiederholungen", restRepsText) { restRepsText = it }
            IntField("Wiederholungen pro Satz", repsText) { repsText = it }
            DurationField("Satzpause", restSetsText) { restSetsText = it }
            IntField("Sätze", setsText) { setsText = it }
            DurationField("Cooldown", cooldownText) { cooldownText = it }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel) { Text("Abbrechen") }
                Button(onClick = {
                    error = null

                    val warmup = parseDuration(warmupText)
                    val work = parseDuration(workText)
                    val restReps = parseDuration(restRepsText)
                    val restSets = parseDuration(restSetsText)
                    val cooldown = parseDuration(cooldownText)
                    val reps = repsText.trim().toIntOrNull()
                    val sets = setsText.trim().toIntOrNull()

                    if (title.trim().isEmpty()) return@Button run { error = "Bitte Titel eintragen." }
                    if (work == null || work <= 0) return@Button run { error = "Arbeitszeit ungültig (z.B. 00:20)." }
                    if (warmup == null || restReps == null || restSets == null || cooldown == null) return@Button run { error = "Eine Zeit ist ungültig (mm:ss)." }
                    if (reps == null || reps <= 0 || sets == null || sets <= 0) return@Button run { error = "Sätze/Wdh müssen > 0 sein." }

                    val timing = TimingConfig(
                        warmupSec = warmup,
                        workSec = work,
                        restBetweenRepsSec = restReps,
                        repsPerSet = reps,
                        restBetweenSetsSec = restSets,
                        sets = sets,
                        cooldownSec = cooldown
                    )

                    val ex = exerciseName.trim().takeIf { it.isNotEmpty() }?.let {
                        Exercise(name = it, notes = exerciseNotes.trim())
                    }

                    val saved = initial?.copy(
                        title = title.trim(),
                        timing = timing,
                        exercise = ex
                    ) ?: IntervalCard(
                        title = title.trim(),
                        timing = timing,
                        exercise = ex
                    )

                    onSave(saved)
                }) { Text("Speichern") }
            }
        }
    }
}

@Composable
private fun DurationField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
    )
}

@Composable
private fun IntField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { new -> if (new.isEmpty() || new.all { it.isDigit() }) onChange(new) },
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RunnerScreen(card: IntervalCard, onBack: () -> Unit) {
    val phases = remember(card) { PhaseBuilder.build(card) }
    val session = remember(card) { IntervalSession(phases) }

    LaunchedEffect(session) {
        while (isActive) {
            session.tick()
            delay(100)
        }
    }
    LaunchedEffect(card.id) { session.start() }

    val ui = session.ui
    val context = LocalContext.current

// Tone (Beep)
    val tone = remember { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }
    DisposableEffect(Unit) {
        onDispose { tone.release() }
    }

// Vibrator
    val vibrator = remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    fun beepShort() {
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
    }

    fun vibrateShort() {
        val effect = VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)
        vibrator.vibrate(effect)
    }

// Phase-Change Feedback
    var lastPhaseIndex by remember { mutableStateOf(-1) }
    LaunchedEffect(ui.phaseIndex, ui.status) {
        if (ui.status == RunStatus.RUNNING && ui.phaseIndex != lastPhaseIndex) {
            lastPhaseIndex = ui.phaseIndex
            beepShort()
            vibrateShort()
        }
    }

// 3-2-1 Countdown nur in ARBEIT
    var lastCountdown by remember { mutableStateOf(-1) }
    LaunchedEffect(ui.remainingSec, ui.phaseType, ui.status) {
        if (ui.status == RunStatus.RUNNING && ui.phaseType == PhaseType.WORK) {
            val r = ui.remainingSec
            if (r in 1..3 && r != lastCountdown) {
                lastCountdown = r
                beepShort()
            }
            if (r > 3) lastCountdown = -1
        } else {
            lastCountdown = -1
        }
    }


    val view = LocalView.current
    DisposableEffect(ui.status) {
        view.keepScreenOn = (ui.status == RunStatus.RUNNING || ui.status == RunStatus.PAUSED)
        onDispose { view.keepScreenOn = false }
    }


    val phaseTitle = when (ui.phaseType) {
        PhaseType.WARMUP -> "WARMUP"
        PhaseType.WORK -> "ARBEIT"
        PhaseType.REST -> "PAUSE"
        PhaseType.COOLDOWN -> "COOLDOWN"
    }

    Scaffold(topBar = { TopAppBar(title = { Text(card.title) }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(phaseTitle, style = MaterialTheme.typography.titleLarge)

            if (!ui.exerciseName.isNullOrBlank() && ui.phaseType == PhaseType.WORK) {
                Text(ui.exerciseName!!, style = MaterialTheme.typography.headlineMedium)
            } else {
                Text(" ", style = MaterialTheme.typography.headlineMedium)
            }

            Text(formatDuration(ui.remainingSec), style = MaterialTheme.typography.displayLarge)
            Text(ui.label)
            Text("Gesamt verbleibend: ${formatDuration(ui.totalRemainingSec)}")

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (ui.status) {
                    RunStatus.IDLE -> Button(onClick = { session.start() }) { Text("Start") }
                    RunStatus.RUNNING -> {
                        Button(onClick = { session.pause() }) { Text("Pause") }
                        OutlinedButton(onClick = { session.stop() }) { Text("Stop") }
                    }
                    RunStatus.PAUSED -> {
                        Button(onClick = { session.resume() }) { Text("Weiter") }
                        OutlinedButton(onClick = { session.stop() }) { Text("Stop") }
                    }
                    RunStatus.FINISHED -> Button(onClick = { session.start() }) { Text("Nochmal") }
                }
                OutlinedButton(onClick = { session.skip() }) { Text("Skip") }
            }

            OutlinedButton(onClick = { session.stop(); onBack() }) { Text("Zurück") }
        }
    }
}
