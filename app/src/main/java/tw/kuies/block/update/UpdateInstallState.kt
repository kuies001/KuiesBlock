package tw.kuies.block.update

/** What the update card is doing, so the dialog can show exactly one phase at a time. */
sealed interface UpdateInstallState {
    data object Idle : UpdateInstallState

    /** [percent] is null while the server has not told us the total size. */
    data class Downloading(val percent: Int?) : UpdateInstallState

    data object Verifying : UpdateInstallState

    /** The APK passed every check; the system installer has been or can be opened. */
    data object Ready : UpdateInstallState

    /** Android needs the user to allow installing unknown apps before the installer can open. */
    data object NeedsInstallPermission : UpdateInstallState

    data class Failed(val reason: UpdateInstallFailure) : UpdateInstallState
}

/** Every way the download or verification can stop, with the sentence the user sees. */
enum class UpdateInstallFailure(val message: String) {
    APK_ASSET_MISSING("找不到官方 APK 更新檔。"),
    CHECKSUM_MISSING("找不到可驗證的更新檔校驗值。"),
    DOWNLOAD_TIMEOUT("下載逾時，請稍後再試。"),
    DOWNLOAD_FAILED("下載更新失敗，請稍後再試。"),
    EMPTY_FILE("下載的更新檔是空的。"),
    SIZE_MISMATCH("更新檔大小不符。"),
    CHECKSUM_MISMATCH("更新檔驗證失敗。"),
    APK_UNREADABLE("更新檔驗證失敗。"),
    PACKAGE_MISMATCH("更新檔驗證失敗。"),
    VERSION_MISMATCH("更新檔驗證失敗。"),
    VERSION_NOT_NEWER("更新檔版本沒有比目前版本新。"),
    SIGNATURE_MISMATCH("更新檔驗證失敗。"),
    INSTALLED_SIGNATURE_MISMATCH(
        "目前安裝的版本使用不同簽章，Android 無法直接覆蓋。請改用相同簽章的版本，資料不會被清除。"
    ),
    INSTALL_PERMISSION_REQUIRED("需要允許 KuiesBlock 安裝未知來源 App。"),
    INSTALLER_UNAVAILABLE("找不到 Android 系統安裝器。"),
    UNKNOWN("更新失敗，請稍後再試。")
}
