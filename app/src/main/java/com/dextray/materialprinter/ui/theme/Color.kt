package com.dextray.materialprinter.ui.theme

import androidx.compose.ui.graphics.Color

// Design idea: a pocket thermal printer. The UI is graphite "ink" on cool paper-grey,
// the only saturated colours are the status LED (green / amber / red) - like on the real device.

// ---- Light: paper ----
val InkPrimary = Color(0xFF2B3A47)
val InkOnPrimary = Color(0xFFFFFFFF)
val InkPrimaryContainer = Color(0xFFDCE6EE)
val InkOnPrimaryContainer = Color(0xFF111C26)
val InkSecondary = Color(0xFF4D6070)
val InkOnSecondary = Color(0xFFFFFFFF)
val InkSecondaryContainer = Color(0xFFE3EAEF)
val InkOnSecondaryContainer = Color(0xFF1A2832)
val HeatTertiary = Color(0xFF8A5100)
val HeatOnTertiary = Color(0xFFFFFFFF)
val HeatTertiaryContainer = Color(0xFFFFDDB4)
val HeatOnTertiaryContainer = Color(0xFF2D1600)
val PaperBackground = Color(0xFFF2F5F3)
val PaperOnBackground = Color(0xFF181D1F)
val PaperSurface = Color(0xFFF6F8F7)
val PaperSurfaceVariant = Color(0xFFDCE3E6)
val PaperOnSurfaceVariant = Color(0xFF40484C)
val PaperOutline = Color(0xFF70787C)
val PaperOutlineVariant = Color(0xFFC0C8CC)
val PaperContainerLowest = Color(0xFFFFFFFF)
val PaperContainerLow = Color(0xFFEEF2F0)
val PaperContainer = Color(0xFFE8EDEB)
val PaperContainerHigh = Color(0xFFE2E8E6)
val PaperContainerHighest = Color(0xFFDCE2E0)

// ---- Dark: printer body ----
val InkDarkPrimary = Color(0xFFBCCDDB)
val InkDarkOnPrimary = Color(0xFF1B2B38)
val InkDarkPrimaryContainer = Color(0xFF33434F)
val InkDarkOnPrimaryContainer = Color(0xFFDCE6EE)
val InkDarkSecondary = Color(0xFFB4C4D2)
val InkDarkOnSecondary = Color(0xFF1F2F3B)
val InkDarkSecondaryContainer = Color(0xFF354551)
val InkDarkOnSecondaryContainer = Color(0xFFD3E4F2)
val HeatDarkTertiary = Color(0xFFFFB95C)
val HeatDarkOnTertiary = Color(0xFF492900)
val HeatDarkTertiaryContainer = Color(0xFF693C00)
val HeatDarkOnTertiaryContainer = Color(0xFFFFDDB4)
val CaseBackground = Color(0xFF0F1416)
val CaseOnBackground = Color(0xFFDFE3E4)
val CaseSurface = Color(0xFF0F1416)
val CaseSurfaceVariant = Color(0xFF40484C)
val CaseOnSurfaceVariant = Color(0xFFC0C8CC)
val CaseOutline = Color(0xFF8A9296)
val CaseOutlineVariant = Color(0xFF40484C)
val CaseContainerLowest = Color(0xFF0A0F11)
val CaseContainerLow = Color(0xFF171C1E)
val CaseContainer = Color(0xFF1B2022)
val CaseContainerHigh = Color(0xFF252B2D)
val CaseContainerHighest = Color(0xFF303638)

// ---- Status LED (same in both themes, like a real LED) ----
val LedGreen = Color(0xFF2FB36B)
val LedAmber = Color(0xFFF0A030)
val LedRed = Color(0xFFE5484D)
val LedOff = Color(0xFF8A949B)

// ---- The receipt itself: always light, it is paper ----
val ReceiptPaperWhite = Color(0xFFFBFCFA)
val ReceiptPaperHint = Color(0xFF8A9096)
val PrinterSlotTop = Color(0xFF2E353B)
val PrinterSlotBottom = Color(0xFF12171B)
val ThermalBlack = Color(0xFF1A1A1A)
