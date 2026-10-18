package tw.kuies.block.update

/** What the update card is doing, so the dialog can show exactly one phase at a time. */
sealed interface UpdateInstallState {
    data object Idle : UpdateInstallState

    /** [percent] is null while the server has not told us the total size. */
    data class Downloading(val percent: Int?) : UpdateInstallState

    data object Verifying : UpdateInstallState

    /** The verified APK is being written into a PackageInstaller session. */
    data object Installing : UpdateInstallState

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
    INSTALLER_UNAVAILABLE("無法開啟 Android 安裝流程。"),
    INSTALL_CANCELLED("已取消安裝。"),
    INSTALL_BLOCKED("Android 已封鎖這次安裝。"),
    INSTALL_CONFLICT("更新檔與目前版本衝突。"),
    INSTALL_INCOMPATIBLE("更新檔與這個 Android 版本不相容。"),
    INSTALL_INVALID("更新檔無效或已損毀。"),
    INSTALL_STORAGE("裝置儲存空間不足。"),
    INSTALL_TIMEOUT("安裝逾時，請稍後再試。"),
    INSTALL_FAILED("Android 安裝失敗，請稍後再試。"),
    UNKNOWN("更新失敗，請稍後再試。")
}
