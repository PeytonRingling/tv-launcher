package com.peyton.tvlauncher

import java.time.LocalDate

/**
 * Verse of the day: REFERENCES ONLY (USFM verse ids). No Scripture text is in the source or the APK; the text is fetched
 * at runtime from API.Bible with Peyton's own key (see Verse.kt) and only cached on the TV.
 *
 * [DAYS] is indexed by the day of a leap year (index 59 = Feb 29), so a date always gets the same verse in every year
 * (Dec 25 is always Luke 2:11). Picked for short, widely quoted verses that fit the 228dp side card; the app still checks
 * each returned passage ([VerseText.fits]) and falls back to [SPARES] (very short verses) if one doesn't fit.
 */
object VerseRefs {
  val DAYS: List<String> = listOf(
    "LAM.3.22", "GEN.1.1", "PSA.118.24", "2CO.5.17", "PRO.3.5", "PRO.3.6", "JHN.1.5", "PSA.46.1", "MAT.6.33", "ROM.8.28",
    "PHP.4.13", "JOS.1.9", "PSA.23.1", "JER.29.11", "MAT.11.28", "JHN.14.27", "1PE.5.7", "PSA.27.1", "HEB.11.1", "ROM.15.13",
    "PSA.119.105", "1TH.5.16", "1TH.5.17", "1TH.5.18", "GAL.5.22", "EPH.2.8", "MIC.6.8", "PSA.37.4", "1CO.16.14", "COL.3.23",
    "PSA.139.14", "JHN.8.12", "PSA.121.1", "PSA.121.2", "PRO.16.3", "HEB.13.8", "JHN.15.5", "PSA.34.8", "PHP.4.4", "ROM.12.12",
    "JAS.1.5", "1JN.4.19", "PSA.103.1", "ISA.26.3", "MAT.5.9", "JHN.13.34", "NUM.6.24", "NUM.6.25", "NUM.6.26", "PSA.4.8",
    "PSA.16.11", "PSA.19.1", "JHN.3.16", "ROM.5.8", "EXO.14.14", "PSA.100.5", "2TI.1.7", "PSA.30.5", "PRO.17.17", "1JN.4.8",
    "EPH.4.32", "GAL.6.2", "PSA.56.3", "ISA.40.8", "MRK.9.23", "LUK.1.37", "PSA.62.1", "1CO.13.4", "1CO.13.13", "PSA.91.1",
    "PSA.91.2", "JHN.10.10", "PSA.28.7", "HEB.10.23", "PRO.18.10", "ROM.12.21", "PSA.145.18", "JHN.14.6", "PSA.51.10", "ROM.6.23",
    "JHN.11.25", "PSA.150.6", "1CO.15.57", "PSA.95.1", "MAT.5.14", "MAT.5.16", "PSA.46.10", "PRO.4.23", "PSA.32.8", "HEB.4.16",
    "JAS.4.8", "PSA.73.26", "JHN.15.12", "PSA.136.1", "ROM.10.13", "2CO.5.7", "GAL.6.9", "PSA.18.2", "MAT.7.7", "PSA.37.5",
    "JHN.8.32", "COL.3.14", "PSA.107.1", "1JN.1.9", "EPH.6.10", "PSA.84.11", "MAT.5.8", "PSA.55.22", "JHN.16.33", "ROM.8.31",
    "PRO.15.1", "PSA.86.5", "1TH.5.11", "PSA.25.4", "HEB.13.5", "2TH.3.3", "PSA.42.11", "LUK.6.31", "PSA.29.11", "1JN.3.18",
    "PSA.103.12", "ZEC.4.6", "ROM.5.1", "JHN.14.1", "PSA.9.10", "PRO.16.9", "MAT.5.3", "PSA.31.24", "1CO.10.31", "ISA.55.6",
    "JAS.1.19", "PSA.118.29", "GAL.5.1", "PSA.145.8", "JHN.6.35", "2CO.9.15", "PSA.27.14", "PHP.1.6", "NAM.1.7", "MAT.22.37",
    "PSA.143.8", "EPH.2.10", "PSA.5.3", "JHN.15.13", "ROM.8.37", "PSA.40.1", "PRO.17.22", "COL.3.2", "PSA.63.3", "JER.33.3",
    "JHN.10.11", "PSA.34.18", "HEB.10.24", "MAT.18.20", "PSA.16.8", "1JN.4.16", "PSA.96.1", "ISA.64.8", "JHN.4.24", "PSA.62.8",
    "PRO.11.25", "MAT.5.4", "PSA.119.11", "ROM.3.23", "PSA.36.5", "2CO.3.17", "PSA.147.3", "JHN.15.9", "PSA.138.8", "GAL.5.14",
    "PSA.119.114", "MAT.5.5", "PSA.33.12", "PHP.4.19", "PSA.98.4", "LUK.18.27", "PSA.71.5", "1JN.4.7", "PSA.86.11", "JHN.17.17",
    "PRO.12.25", "MAT.5.6", "PSA.90.12", "ROM.15.7", "PSA.94.19", "EPH.4.2", "PSA.103.8", "2TI.4.7", "PSA.133.1", "JHN.1.1",
    "PRO.22.6", "MAT.5.7", "PSA.40.8", "1PE.4.8", "PSA.111.10", "JHN.20.29", "PSA.116.1", "ROM.13.10", "PSA.127.1", "JAS.1.22",
    "PSA.126.3", "MAT.6.21", "PSA.145.3", "HEB.13.16", "PSA.92.1", "PHP.2.4", "PSA.104.33", "2CO.4.16", "PSA.113.3", "JHN.14.15",
    "PRO.27.17", "MAT.6.34", "PSA.115.1", "1TI.6.6", "PSA.117.1", "JHN.15.11", "PSA.119.18", "COL.4.2", "PSA.119.68", "PRO.30.5",
    "MAT.11.29", "PSA.119.89", "JAS.4.10", "PSA.119.130", "JHN.12.46", "PSA.130.5", "ROM.14.19", "PSA.135.3", "2CO.4.18", "PRO.31.25",
    "MAT.11.30", "PSA.138.3", "1JN.4.18", "PSA.141.3", "JHN.7.38", "PSA.146.2", "EPH.5.2", "PSA.147.5", "PRO.9.10", "MAT.19.26",
    "PSA.149.4", "1PE.5.6", "PSA.37.3", "JHN.10.27", "PSA.48.14", "ROM.11.36", "PSA.50.15", "COL.3.15", "PRO.10.12", "MAT.22.39",
    "PSA.62.5", "PSA.73.28", "JHN.14.18", "PSA.90.2", "PHP.3.14", "PSA.97.1", "TIT.2.11", "PRO.11.2", "MRK.10.27", "PSA.100.4",
    "2TH.3.16", "PSA.112.7", "PSA.118.6", "HEB.12.14", "PSA.118.14", "1JN.5.4", "PRO.12.22", "MRK.11.24", "PSA.119.97", "1CO.16.13",
    "PSA.121.8", "ACT.4.12", "PSA.145.9", "GAL.5.25", "PSA.8.1", "3JN.1.4", "PRO.13.20", "MRK.16.15", "PSA.3.3", "EPH.1.7",
    "PSA.18.1", "ACT.16.31", "PSA.20.4", "COL.1.17", "PSA.31.14", "1TI.2.5", "PRO.14.26", "EPH.5.20", "PSA.57.10", "PSA.59.16",
    "1TH.5.24", "PSA.68.19", "2TI.2.13", "PRO.15.3", "MRK.1.17", "PSA.85.10", "PHP.2.13", "PSA.89.1", "ACT.2.21", "PSA.9.9",
    "HEB.13.1", "PSA.18.28", "1JN.2.17", "PRO.16.20", "LUK.1.45", "PSA.25.1", "PHP.3.20", "PSA.39.7", "HEB.13.2", "1JN.5.3",
    "PRO.16.24", "LUK.6.27", "PSA.54.4", "PHP.1.21", "PSA.59.17", "PSA.65.11", "JAS.1.12", "PSA.9.1", "PRO.19.21", "LUK.6.36",
    "PSA.18.30", "COL.3.12", "PSA.19.14", "ROM.8.1", "PSA.25.5", "JAS.1.17", "PSA.33.4", "2JN.1.6", "PRO.21.21", "LUK.11.9",
    "PSA.34.1", "COL.3.17", "PSA.34.4", "ROM.10.17", "PSA.47.1", "1PE.4.10", "REV.3.20", "PRO.22.1", "LUK.12.32", "COL.4.6",
    "PSA.106.1", "ROM.12.10", "PSA.100.1", "2PE.3.18", "PSA.100.2", "REV.22.13", "PRO.25.11", "LUK.19.10", "PSA.103.2", "1CO.13.7",
    "PSA.117.2", "ROM.13.8", "PSA.119.165", "1PE.1.25", "PSA.121.3", "REV.1.8", "MAT.1.23", "ISA.7.14", "LUK.2.10", "LUK.2.11",
    "LUK.2.14", "PRO.29.25", "LUK.24.6", "1CO.13.8", "PSA.44.8", "LAM.3.23",
  )

  /** Very short fallbacks, used only when a day's passage comes back too long for the card or isn't in the NIV. */
  val SPARES: List<String> = listOf("JHN.11.35", "1JN.4.11", "JHN.10.30", "MAL.3.6", "JHN.6.47", "JHN.3.30", "PRO.15.13", "DEU.6.6", "1JN.1.5", "MAT.10.31", "MAT.24.35", "JOB.42.2", "ECC.3.1", "1TH.4.18", "PHP.4.5", "JER.10.6")

  /** Index into [DAYS]: Jan 1 = 0, Feb 29 = 59, Mar 1 = 60 in every year, Dec 31 = 365. */
  fun index(d: LocalDate): Int = if (d.isLeapYear || d.dayOfYear < 60) d.dayOfYear - 1 else d.dayOfYear

  fun forDate(d: LocalDate): String = DAYS[index(d)]

  /** Day's verse first, then [n] spares (rotating by day so the same spare isn't used all year). */
  fun candidates(d: LocalDate, n: Int = 2): List<String> = listOf(forDate(d)) + List(n) { SPARES[(index(d) + it) % SPARES.size] }
}
