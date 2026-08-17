# BORAT Android

Standalone Android frontend prototype for **BORAT — BLAST Output Refinement by Ancestral Taxonomy**.

Current prototype scope:

- native Android / Jetpack Compose UI
- paired FASTQ / FASTQ.GZ import through Android's system file picker
- local quick FASTQ QC
- scans at most 25,000 read pairs
- pair-preserving quick downsampling
- creates approximately 2 MiB `blast_input.fasta`
- creates `pair_manifest.tsv`
- original FASTQ files are streamed and are not copied into app storage
- mock analysis layer prepared for later Python integration

## Quick FASTQ preparation

The current phone-side preparation is intentionally pragmatic for large raw datasets. It reads only the first up to 25,000 complete read pairs, performs QC on that inspected prefix, and then writes a paired random subset until the BLAST FASTA reaches approximately 2 MiB.

R1 and R2 are always sampled together. The manifest preserves the original pair ID and mate information.

No trimming is applied. Adapter and quality signals are reported as QC warnings instead.

## GitHub Actions APK

Every push to `main` runs `.github/workflows/android-debug-apk.yml`. The workflow builds `app-debug.apk` and uploads it as the artifact **BORAT-debug-apk**.

To install on a phone: open the latest successful **Build BORAT debug APK** workflow run on GitHub, download the `BORAT-debug-apk` artifact, unzip it, transfer `app-debug.apk` to the Android device, and open it. Android may ask you to allow installing unknown apps for the browser/file manager used to open the APK.

The Android Studio emulator is useful for UI testing, but the paired FASTQ import / USB-storage workflow is best tested on a real Android phone.
