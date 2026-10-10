package com.civicwalk.app

/**
 * The on-device model library.
 *
 * Every entry is an open-weight Gemma build in LiteRT-LM's `.litertlm` format, so the same file the
 * desktop harness runs is the file the phone loads. Sizes are the real byte counts reported by the
 * Hugging Face API, and the app shows the byte count it actually received after a download — the
 * numbers in this table are never presented to the user as "verified".
 *
 * No weights are bundled in the APK and none are committed to the repository.
 */
data class ModelEntry(
    val id: String,
    val label: String,
    val fileName: String,
    val repo: String,
    val sizeBytes: Long,
    /** true when the build can take an image as input (the note is then written from the photo). */
    val vision: Boolean,
    /** true when Hugging Face requires a logged-in licence accept + read token. */
    val gated: Boolean,
    val noteZh: String,
    val noteEn: String
) {
    val url: String get() = "https://huggingface.co/$repo/resolve/main/$fileName"
    val sizeLabel: String
        get() = if (sizeBytes >= 1_000_000_000L) "%.2f GB".format(sizeBytes / 1_000_000_000.0)
        else "%.0f MB".format(sizeBytes / 1_000_000.0)
}

object ModelCatalog {

    /** Default: the multimodal build, because a walk note should be about the photo you took. */
    val DEFAULT_ID = "gemma-4-e2b"

    val entries: List<ModelEntry> = listOf(
        ModelEntry(
            id = "gemma-4-e2b",
            label = "Gemma 4 E2B",
            fileName = "gemma-4-E2B-it.litertlm",
            repo = "litert-community/gemma-4-E2B-it-litert-lm",
            sizeBytes = 2_588_147_712L,
            vision = true,
            gated = false,
            noteZh = "睇相 + 寫字 · 質素最好 · 約 2.6 GB 記憶體",
            noteEn = "Sees the photo and writes · best quality · ~2.6 GB in memory"
        ),
        ModelEntry(
            id = "gemma-3-1b",
            label = "Gemma 3 1B",
            fileName = "gemma3-1b-it-int4.litertlm",
            repo = "litert-community/Gemma3-1B-IT",
            sizeBytes = 584_417_280L,
            vision = false,
            gated = true,
            noteZh = "純文字 · 細啲快啲 · 睇唔到相，只憑你打嘅關鍵詞寫",
            noteEn = "Text only · smaller and faster · cannot see the photo, writes from your keywords"
        ),
        ModelEntry(
            id = "gemma-3-270m",
            label = "Gemma 3 270M",
            fileName = "gemma3-270m-it-q8.litertlm",
            repo = "litert-community/gemma-3-270m-it",
            sizeBytes = 304_005_120L,
            vision = false,
            gated = true,
            noteZh = "極細 · 只適合試機／最舊嘅機 · 質素有限",
            noteEn = "Tiny · for testing or very old phones · limited quality"
        )
    )

    fun byId(id: String): ModelEntry? = entries.firstOrNull { it.id == id }

    fun default(): ModelEntry = byId(DEFAULT_ID) ?: entries.first()
}
