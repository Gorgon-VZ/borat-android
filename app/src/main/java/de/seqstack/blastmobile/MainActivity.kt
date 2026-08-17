package de.seqstack.blastmobile

import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.seqstack.blastmobile.analysis.FastqQuickSampler
import de.seqstack.blastmobile.model.FastqPreparationResult
import de.seqstack.blastmobile.model.QcStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BoratDark = Color(0xFF07554F)
private val BoratOlive = Color(0xFF668442)
private val Bg = Color(0xFFF7F9FB)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = BoratDark,
                    secondary = BoratOlive,
                    background = Bg,
                    surface = Color.White,
                    primaryContainer = Color(0xFFE4F0E8),
                )
            ) { BoratApp(::displayName) }
        }
    }

    private fun displayName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "input.fastq"
        val cursor: Cursor? = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            val i = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && it.moveToFirst()) name = it.getString(i)
        }
        return name
    }
}

private enum class Tab { HOME, IMPORT, JOBS, REPORT }

@Composable
private fun BoratApp(displayName: (Uri) -> String) {
    var tab by remember { mutableStateOf(Tab.HOME) }
    var r1 by remember { mutableStateOf<FastqQuickSampler.InputFastq?>(null) }
    var r2 by remember { mutableStateOf<FastqQuickSampler.InputFastq?>(null) }
    var result by remember { mutableStateOf<FastqPreparationResult?>(null) }
    var stage by remember { mutableStateOf("Ready") }
    var progress by remember { mutableFloatStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val sampler = remember { FastqQuickSampler(context) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.size != 2) {
            error = "Please select exactly two paired FASTQ files (R1 and R2)."
            return@rememberLauncherForActivityResult
        }
        val a = FastqQuickSampler.InputFastq(uris[0], displayName(uris[0]))
        val b = FastqQuickSampler.InputFastq(uris[1], displayName(uris[1]))
        val ordered = orderMates(a, b)
        r1 = ordered.first; r2 = ordered.second
        result = null; error = null; stage = "Paired FASTQs selected"; progress = 0f
        tab = Tab.IMPORT
    }

    fun runQuickQc() {
        val a = r1 ?: return
        val b = r2 ?: return
        busy = true; error = null; stage = "Opening FASTQs"; progress = 0f
        scope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) {
                    sampler.prepare(a, b, "SAM-${System.currentTimeMillis()}") { s, p ->
                        scope.launch { stage = s; progress = p }
                    }
                }
                result = prepared; stage = "Quick QC + downsampling complete"; progress = 1f
                tab = Tab.HOME
            } catch (t: Throwable) {
                error = t.message ?: "QC failed"
                stage = "Error"
            } finally { busy = false }
        }
    }

    Scaffold(
        containerColor = Bg,
        bottomBar = {
            NavigationBar {
                listOf(
                    Triple(Tab.HOME, Icons.Default.Home, "Home"),
                    Triple(Tab.IMPORT, Icons.Default.UploadFile, "Import"),
                    Triple(Tab.JOBS, Icons.Default.ListAlt, "Jobs"),
                    Triple(Tab.REPORT, Icons.Default.Description, "Report"),
                ).forEach { (t, icon, label) ->
                    NavigationBarItem(selected = tab == t, onClick = { tab = t }, icon = { Icon(icon, null) }, label = { Text(label) })
                }
            }
        }
    ) { pad ->
        when (tab) {
            Tab.HOME -> HomeScreen(Modifier.padding(pad), result, stage, onImport = { tab = Tab.IMPORT })
            Tab.IMPORT -> ImportScreen(Modifier.padding(pad), r1, r2, result, busy, stage, progress, error,
                onPick = { picker.launch(arrayOf("*/*")) }, onRun = ::runQuickQc)
            Tab.JOBS -> SimpleScreen(Modifier.padding(pad), "Jobs", result?.let { "Latest quick preparation: ${it.selectedPairs} pairs" } ?: "No local jobs yet")
            Tab.REPORT -> SimpleScreen(Modifier.padding(pad), "Report", result?.let { "QC data ready. HTML/PDF export will be added next." } ?: "Run QC first")
        }
    }
}

@Composable
private fun HomeScreen(modifier: Modifier, result: FastqPreparationResult?, stage: String, onImport: () -> Unit) {
    LazyColumn(modifier = modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Spacer(Modifier.height(10.dp)); BoratLogo(); Spacer(Modifier.height(8.dp)) }
        item {
            Button(onClick = onImport, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.UploadFile, null); Spacer(Modifier.width(8.dp)); Text("USB/Local Import", fontWeight = FontWeight.Bold)
            }
        }
        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text("Current Sample", color = BoratDark, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(if (result == null) "No sample loaded" else "Local FASTQ sample", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(result?.let { "${it.r1Name}\n${it.r2Name}" } ?: stage, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (result != null) {
                        Spacer(Modifier.height(10.dp))
                        Text("✓ R1/R2 paired")
                        Text("✓ Quick sampling")
                        Text("✓ %.2f MiB BLAST FASTA ready".format(result.fastaBytes / 1048576.0))
                    }
                }
            }
        }
        if (result != null) item { QcCard(result) }
        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp)) {
                    Text("Quick mode", color = BoratDark, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("Only the first 25,000 read pairs are inspected. The original FASTQs are streamed, not copied. A paired random subset is written until the BLAST FASTA reaches about 2 MiB.")
                }
            }
        }
        item { Spacer(Modifier.height(14.dp)) }
    }
}

@Composable
private fun ImportScreen(
    modifier: Modifier,
    r1: FastqQuickSampler.InputFastq?, r2: FastqQuickSampler.InputFastq?, result: FastqPreparationResult?,
    busy: Boolean, stage: String, progress: Float, error: String?, onPick: () -> Unit, onRun: () -> Unit,
) {
    LazyColumn(modifier = modifier.fillMaxSize().padding(horizontal = 18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Spacer(Modifier.height(12.dp)); Text("USB / Local Import", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Text("Choose paired raw FASTQ or FASTQ.GZ files") }
        item {
            Card(shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(18.dp)) {
                    FilledTonalButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) { Text("Choose R1 + R2") }
                    Spacer(Modifier.height(12.dp))
                    Text("R1: ${r1?.name ?: "not selected"}")
                    Text("R2: ${r2?.name ?: "not selected"}")
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = onRun, enabled = r1 != null && r2 != null && !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Processing…" else "Run local QC + downsampling") }
                    if (busy) { Spacer(Modifier.height(12.dp)); LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth()); Text(stage, style = MaterialTheme.typography.bodySmall) }
                    error?.let { Spacer(Modifier.height(10.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        if (result != null) item { QcCard(result) }
    }
}

@Composable
private fun QcCard(result: FastqPreparationResult) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("QC Summary", color = BoratDark, fontWeight = FontWeight.Bold)
                Text("${result.qc.inspectedPairs} pairs", color = BoratOlive)
            }
            Spacer(Modifier.height(12.dp))
            result.qc.tiles.chunked(5).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { tile ->
                        val c = when(tile.status) { QcStatus.PASS -> Color(0xFFE6F3E9); QcStatus.WARN -> Color(0xFFFFF1C7); QcStatus.FAIL -> Color(0xFFFFDAD6) }
                        Box(Modifier.weight(1f).height(52.dp).background(c, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(tile.label, fontSize = 8.sp); Text(tile.value, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text("Selected ${result.selectedPairs} paired reads · %.2f MiB FASTA".format(result.fastaBytes/1048576.0), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SimpleScreen(modifier: Modifier, title: String, text: String) {
    Column(modifier.fillMaxSize().padding(18.dp)) { Spacer(Modifier.height(12.dp)); Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold); Spacer(Modifier.height(16.dp)); Card { Text(text, Modifier.padding(18.dp)) } }
}

@Composable
private fun BoratLogo() {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("BORAT", color = BoratDark, fontSize = 32.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
            Spacer(Modifier.width(8.dp))
            Canvas(Modifier.width(64.dp).height(38.dp)) {
                val stroke = 2.dp.toPx(); val y0 = 1.dp.toPx(); val y1 = size.height*.30f; val y2 = size.height*.56f; val y3 = size.height*.78f; val y4=size.height
                val xs = floatArrayOf(.04f,.15f,.31f,.42f,.58f,.69f,.85f,.96f).map { it*size.width }
                fun ln(x1:Float,y1v:Float,x2:Float,y2v:Float)=drawLine(BoratOlive,Offset(x1,y1v),Offset(x2,y2v),stroke,StrokeCap.Square)
                val pc = FloatArray(4)
                for(i in 0..3){ val l=xs[i*2]; val r=xs[i*2+1]; val c=(l+r)/2; pc[i]=c; ln(l,y0,l,y1); ln(r,y0,r,y1); ln(l,y1,r,y1); ln(c,y1,c,y2) }
                val lc=(pc[0]+pc[1])/2; val rc=(pc[2]+pc[3])/2
                ln(pc[0],y2,pc[1],y2); ln(pc[2],y2,pc[3],y2); ln(lc,y2,lc,y3); ln(rc,y2,rc,y3); ln(lc,y3,rc,y3); ln(size.width*.5f,y3,size.width*.5f,y4)
            }
        }
        Text("BLAST OUTPUT REFINEMENT BY ANCESTRAL TAXONOMY", color = BoratOlive, fontSize = 7.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .35.sp)
    }
}

private fun orderMates(a: FastqQuickSampler.InputFastq, b: FastqQuickSampler.InputFastq): Pair<FastqQuickSampler.InputFastq, FastqQuickSampler.InputFastq> {
    fun mate(n:String):Int? { val s=n.lowercase(); return when { Regex("(^|[_.-])r?1([_.-]|$)").containsMatchIn(s) -> 1; Regex("(^|[_.-])r?2([_.-]|$)").containsMatchIn(s) -> 2; else -> null } }
    return if (mate(a.name)==2 && mate(b.name)==1) b to a else a to b
}
