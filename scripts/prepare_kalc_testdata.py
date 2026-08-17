#!/usr/bin/env python3
"""Create the small paired Kal_C FASTQ test dataset embedded in the BORAT APK.

Input is the public ENA run ERR3479086. The first 11,300 complete read pairs are
validated and recompressed. With the current archive files this produces about
2.0 MiB total compressed test data while retaining enough sequence for BORAT's
~2 MiB paired BLAST FASTA quick-sampling target.
"""

from __future__ import annotations

import gzip
import os
import sys
from pathlib import Path

PAIR_COUNT = 11_300
MIN_TOTAL_BYTES = int(1.8 * 1024 * 1024)
MAX_TOTAL_BYTES = int(2.2 * 1024 * 1024)


def canonical_id(header: str) -> str:
    value = header.strip().removeprefix("@").split(" ", 1)[0]
    if value.endswith("/1") or value.endswith("/2"):
        value = value[:-2]
    return value


def read_record(handle):
    lines = [handle.readline() for _ in range(4)]
    if not lines[0]:
        return None
    if any(line == "" for line in lines):
        raise RuntimeError("Truncated FASTQ record")
    if not lines[0].startswith("@") or not lines[2].startswith("+"):
        raise RuntimeError("Invalid FASTQ record")
    return lines


def main() -> int:
    if len(sys.argv) != 5:
        print("usage: prepare_kalc_testdata.py INPUT_R1 INPUT_R2 OUTPUT_R1 OUTPUT_R2", file=sys.stderr)
        return 2

    input_r1, input_r2, output_r1, output_r2 = map(Path, sys.argv[1:])
    output_r1.parent.mkdir(parents=True, exist_ok=True)
    output_r2.parent.mkdir(parents=True, exist_ok=True)

    written = 0
    with gzip.open(input_r1, "rt", encoding="ascii", newline="") as r1, \
         gzip.open(input_r2, "rt", encoding="ascii", newline="") as r2, \
         gzip.open(output_r1, "wt", encoding="ascii", newline="", compresslevel=6) as out1, \
         gzip.open(output_r2, "wt", encoding="ascii", newline="", compresslevel=6) as out2:
        for _ in range(PAIR_COUNT):
            rec1 = read_record(r1)
            rec2 = read_record(r2)
            if rec1 is None or rec2 is None:
                raise RuntimeError(f"Input ended before {PAIR_COUNT:,} paired reads")
            if canonical_id(rec1[0]) != canonical_id(rec2[0]):
                raise RuntimeError(
                    f"Pair mismatch at record {written + 1}: "
                    f"{rec1[0].strip()} vs {rec2[0].strip()}"
                )
            out1.writelines(rec1)
            out2.writelines(rec2)
            written += 1

    size1 = os.path.getsize(output_r1)
    size2 = os.path.getsize(output_r2)
    total = size1 + size2
    if not MIN_TOTAL_BYTES <= total <= MAX_TOTAL_BYTES:
        raise RuntimeError(
            f"Unexpected bundled dataset size: {total / 1048576:.2f} MiB "
            f"(expected 1.8–2.2 MiB)"
        )

    print(f"Kal_C test data: {written:,} paired reads")
    print(f"R1: {size1 / 1048576:.2f} MiB")
    print(f"R2: {size2 / 1048576:.2f} MiB")
    print(f"Total: {total / 1048576:.2f} MiB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
