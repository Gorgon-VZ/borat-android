package de.seqstack.blastmobile.analysis

import android.content.Context
import android.net.Uri
import de.seqstack.blastmobile.model.FastqPreparationResult
import de.seqstack.blastmobile.model.FastqQcSummary
import de.seqstack.blastmobile.model.QcStatus
import de.seqstack.blastmobile.model.QcTile
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Collections
import java.util.Random
import java.util.zip.GZIPInputStream
import kotlin.math.max

class FastqQuickSampler(private val context: Context) {
    data class InputFastq(val uri: Uri, val name: String)
    private data class Record(val header: String, val seq: String, val qual: String)
    private data class PairRec(val id: String, val r1: String, val r2: String)

    companion object {
        const val MAX_PAIRS = 25_000
        const val TARGET_FASTA_BYTES = 2L * 1024L * 1024L
        private const val RESERVOIR_CAPACITY = 15_000
        private val ADAPTERS = listOf("AGATCGGAAGAG", "CTGTCTCTTATACACATCT")
    }

    fun prepare(
        r1: InputFastq,
        r2: InputFastq,
        sampleId: String,
        onProgress: (String, Float) -> Unit = { _, _ -> },
    ): FastqPreparationResult {
        val random = Random(System.currentTimeMillis())
        val reservoir = ArrayList<PairRec>(RESERVOIR_CAPACITY)
        var inspected = 0
        var mismatches = 0
        var bases1 = 0L; var bases2 = 0L
        var qsum1 = 0L; var qsum2 = 0L
        var q30_1 = 0L; var q30_2 = 0L
        var gc = 0L; var n = 0L
        var adapter1 = 0; var adapter2 = 0
        var len1 = 0L; var len2 = 0L

        openReader(r1).use { a ->
            openReader(r2).use { b ->
                while (inspected < MAX_PAIRS) {
                    val x = readRecord(a) ?: break
                    val y = readRecord(b) ?: break
                    inspected++
                    val id1 = canonicalId(x.header)
                    val id2 = canonicalId(y.header)
                    if (id1 != id2) mismatches++
                    len1 += x.seq.length; len2 += y.seq.length
                    bases1 += x.seq.length; bases2 += y.seq.length
                    x.seq.forEach { c -> when (c.uppercaseChar()) { 'G','C' -> gc++; 'N' -> n++ } }
                    y.seq.forEach { c -> when (c.uppercaseChar()) { 'G','C' -> gc++; 'N' -> n++ } }
                    x.qual.forEach { c -> val q=max(0,c.code-33); qsum1+=q; if(q>=30) q30_1++ }
                    y.qual.forEach { c -> val q=max(0,c.code-33); qsum2+=q; if(q>=30) q30_2++ }
                    if (hasAdapter(x.seq)) adapter1++
                    if (hasAdapter(y.seq)) adapter2++
                    val pair = PairRec(if(id1==id2) id1 else "$id1|$id2", x.seq, y.seq)
                    if (reservoir.size < RESERVOIR_CAPACITY) reservoir += pair else {
                        val j = random.nextInt(inspected)
                        if (j < RESERVOIR_CAPACITY) reservoir[j] = pair
                    }
                    if (inspected % 500 == 0) onProgress("Quick QC · $inspected / $MAX_PAIRS pairs", inspected.toFloat()/MAX_PAIRS)
                }
            }
        }
        require(inspected > 0) { "No complete read pairs found" }

        Collections.shuffle(reservoir, random)
        val dir = File(context.filesDir, "jobs/$sampleId").apply { mkdirs() }
        val fasta = File(dir, "blast_input.fasta")
        val manifest = File(dir, "pair_manifest.tsv")
        var bytes = 0L
        var selected = 0
        fasta.bufferedWriter(Charsets.US_ASCII).use { fw ->
            manifest.bufferedWriter().use { mw ->
                mw.write("pair_id\toriginal_read_id\tmate\n")
                for (p in reservoir) {
                    val pid = "pair_${(selected+1).toString().padStart(6,'0')}"
                    val e1 = ">$pid" + "_R1\n${p.r1}\n"
                    val e2 = ">$pid" + "_R2\n${p.r2}\n"
                    fw.write(e1); fw.write(e2)
                    mw.write("$pid\t${p.id}\tR1\n$pid\t${p.id}\tR2\n")
                    bytes += e1.toByteArray(Charsets.US_ASCII).size + e2.toByteArray(Charsets.US_ASCII).size
                    selected++
                    if (bytes >= TARGET_FASTA_BYTES) break
                }
            }
        }

        val allBases = bases1 + bases2
        val meanQ1 = qsum1.toDouble()/bases1.coerceAtLeast(1)
        val meanQ2 = qsum2.toDouble()/bases2.coerceAtLeast(1)
        val q301 = q30_1.toDouble()/bases1.coerceAtLeast(1)
        val q302 = q30_2.toDouble()/bases2.coerceAtLeast(1)
        val nf = n.toDouble()/allBases.coerceAtLeast(1)
        val gcf = gc.toDouble()/allBases.coerceAtLeast(1)
        val ad1 = adapter1.toDouble()/inspected
        val ad2 = adapter2.toDouble()/inspected
        fun quality(v:Double)=if(v>=28) QcStatus.PASS else if(v>=20) QcStatus.WARN else QcStatus.FAIL
        fun q30(v:Double)=if(v>=.80) QcStatus.PASS else if(v>=.60) QcStatus.WARN else QcStatus.FAIL
        fun frac(v:Double, pass:Double, warn:Double)=if(v<=pass) QcStatus.PASS else if(v<=warn) QcStatus.WARN else QcStatus.FAIL
        val qc = FastqQcSummary(
            inspected, mismatches,
            len1.toDouble()/inspected, len2.toDouble()/inspected,
            meanQ1, meanQ2, q301, q302, nf, gcf, ad1, ad2,
            listOf(
                QcTile("Pairing", if(mismatches==0) "OK" else "$mismatches mismatch", if(mismatches==0) QcStatus.PASS else QcStatus.FAIL),
                QcTile("Mean Q R1", "%.1f".format(meanQ1), quality(meanQ1)),
                QcTile("Mean Q R2", "%.1f".format(meanQ2), quality(meanQ2)),
                QcTile("Q30 R1", "%.0f%%".format(q301*100), q30(q301)),
                QcTile("Q30 R2", "%.0f%%".format(q302*100), q30(q302)),
                QcTile("N content", "%.2f%%".format(nf*100), frac(nf,.005,.02)),
                QcTile("Length", "%.0f/%.0f".format(len1.toDouble()/inspected,len2.toDouble()/inspected), QcStatus.PASS),
                QcTile("Adapter R1", "%.1f%%".format(ad1*100), frac(ad1,.05,.20)),
                QcTile("Adapter R2", "%.1f%%".format(ad2*100), frac(ad2,.05,.20)),
                QcTile("BLAST sample", "%.1f MiB".format(bytes/1048576.0), if(bytes>=TARGET_FASTA_BYTES*.9) QcStatus.PASS else QcStatus.WARN),
            )
        )
        onProgress("Quick sample ready",1f)
        return FastqPreparationResult(r1.name,r2.name,inspected,selected,bytes,fasta.absolutePath,manifest.absolutePath,qc)
    }

    private fun openReader(input: InputFastq): BufferedReader {
        val raw = context.contentResolver.openInputStream(input.uri) ?: error("Cannot open ${input.name}")
        val buffered = BufferedInputStream(raw,128*1024)
        buffered.mark(4); val a=buffered.read(); val b=buffered.read(); buffered.reset()
        val stream = if(a==0x1f && b==0x8b) GZIPInputStream(buffered,128*1024) else buffered
        return BufferedReader(InputStreamReader(stream,Charsets.US_ASCII),128*1024)
    }
    private fun readRecord(r:BufferedReader):Record? {
        val h=r.readLine()?:return null; val s=r.readLine()?:error("Truncated FASTQ")
        val plus=r.readLine()?:error("Truncated FASTQ"); val q=r.readLine()?:error("Truncated FASTQ")
        require(h.startsWith("@") && plus.startsWith("+")) { "Invalid FASTQ format" }
        require(s.length==q.length) { "Sequence/quality length mismatch" }
        return Record(h,s,q)
    }
    private fun canonicalId(h:String)=h.removePrefix("@").substringBefore(' ').removeSuffix("/1").removeSuffix("/2")
    private fun hasAdapter(s:String):Boolean { val u=s.uppercase(); return ADAPTERS.any { u.contains(it) } }
}
