package com.starfall.gsadrive

import java.util.Locale

/**
 * Returns application copy in the language selected by Android.
 *
 * Vietnamese deliberately remains the only non-default locale. Every other
 * device language falls back to English, so adding a new translation is an
 * explicit decision rather than accidentally exposing Vietnamese copy.
 */
fun tr(vietnamese: String): String = AppLanguage.translate(vietnamese, Locale.getDefault())

internal object AppLanguage {
    private val english = mapOf(
        "Mở bằng ứng dụng khác" to "Open with another app",
        "Sao chép" to "Copy",
        "Sao chép vào đây" to "Copy here",
        "Xóa thư mục và toàn bộ nội dung bên trong?" to "Delete this folder and all its contents?",
        "Xóa tệp này?" to "Delete this file?",
        "Tải lên tài khoản cloud" to "Upload to cloud account",
        "Đường dẫn" to "Path",
        "Không thể hoàn tất tải lên." to "Could not finish uploading.",
        "Tệp Hệ Thống" to "System Files",
        "Tài khoản trên thiết bị" to "Account on this device",
        "Nhập thông tin kết nối" to "Enter connection details",
        "Nhập file JSON" to "Import a JSON file",
        "Không thể mở cài đặt quyền truy cập bộ nhớ." to "Could not open storage access settings.",
        "Không thể đọc thư mục này. Kiểm tra quyền truy cập." to "Cannot read this folder. Check access permissions.",
        "Cho phép truy cập bộ nhớ để duyệt tệp trên thiết bị." to "Allow storage access to browse files on this device.",
        "Cấp quyền truy cập" to "Grant access",
        "Tìm trong thư mục" to "Search this folder",
        "Không thể đọc tệp này." to "Cannot read this file.",
        "Không thể mở luồng media S3." to "Could not open the S3 media stream.",
        "Xóa" to "Delete",
        "Không thể xóa một số mục S3. Kiểm tra quyền xóa và thử lại." to
            "Some S3 items could not be deleted. Check delete permissions and try again.",
        "Tài khoản" to "Accounts", "Đóng" to "Close", "Chưa có tài khoản." to "No accounts yet.",
        "Thêm tài khoản khác" to "Add another account", "Cài đặt" to "Settings", "Quay lại" to "Back",
        "Tệp" to "Files", "Chia sẻ" to "Shared", "Thùng rác" to "Trash", "Mở menu" to "Open menu",
        "Tải lên" to "Upload", "Tải thư mục" to "Upload folder", "Thư mục" to "Folder",
        "Đóng menu tạo mới" to "Close create menu", "Tạo mới" to "Create new", "Thư mục mới" to "New folder",
        "Tên thư mục" to "Folder name", "Tạo" to "Create", "Hủy" to "Cancel", "Thêm tài khoản" to "Add account",
        "Google · Tài khoản trên thiết bị" to "Google · Account on this device",
        "S3 · Nhập thông tin kết nối" to "S3 · Enter connection details",
        "Service Account · Nhập file JSON" to "Service Account · Import JSON file",
        "Đăng xuất" to "Sign out", "Tệp của bạn" to "Your files",
        "Kết nối Google, S3 hoặc Service Account để xem tệp." to "Connect Google, S3, or a Service Account to view files.",
        "Tài khoản đã lưu" to "Saved accounts", "Chia sẻ với tôi" to "Shared with me",
        "Chưa có tệp để hiển thị." to "No files to display.", "Lưu trữ cùng Google Drive" to "Storage with Google Drive",
        "Thêm tài khoản để quản lý tệp." to "Add an account to manage files.",
        "Bấm biểu tượng tài khoản để chuyển hoặc thêm tài khoản." to "Tap the account icon to switch or add an account.",
        "Cho phép Drive" to "Allow Drive access", "Tải tệp lên" to "Upload files",
        "Không có tệp trong vị trí này." to "There are no files in this location.", "Thùng rác đang trống." to "Trash is empty.",
        "Thêm tài khoản S3" to "Add S3 account", "Tên tài khoản" to "Account name", "Kết nối và lưu" to "Connect and save",
        "Khôi phục" to "Restore", "Chuyển vào thùng rác" to "Move to trash", "Tìm trong Drive" to "Search Drive",
        "Xóa tìm kiếm" to "Clear search", "Đóng tìm kiếm" to "Close search", "Tìm kiếm" to "Search",
        "Tùy chọn" to "Options", "Làm mới" to "Refresh", "Bỏ chọn tất cả" to "Clear selection",
        "Chọn tất cả" to "Select all", "Sắp xếp theo" to "Sort by", "Danh sách" to "List", "Lưới" to "Grid",
        "Không tìm thấy tệp phù hợp." to "No matching files found.", "Đã chọn" to "Selected", "Chưa chọn" to "Not selected",
        "Tệp được chia sẻ" to "Shared file", "Được chia sẻ" to "Shared", "Đã chỉnh sửa" to "Modified",
        "Tên" to "Name", "Ngày sửa đổi" to "Date modified", "Ngày chia sẻ" to "Date shared",
        "A đến Z" to "A to Z", "Z đến A" to "Z to A", "Từ cũ đến mới" to "Oldest first", "Từ mới đến cũ" to "Newest first",
        "Tháng này" to "This month", "Tháng trước" to "Last month", "Đầu năm nay" to "Earlier this year", "Cũ hơn" to "Older",
        "Di chuyển" to "Move", "Tải lên Photos" to "Upload to Photos", "Quản lý quyền truy cập" to "Manage access",
        "Sao chép đường liên kết" to "Copy link", "Đã sao chép đường liên kết." to "Link copied.", "Đổi tên" to "Rename",
        "Tải lên Google Photos" to "Upload to Google Photos", "Tải xuống" to "Download", "Xem thông tin" to "View details",
        "Người xem" to "Viewer", "Người chỉnh sửa" to "Editor", "Tên mới" to "New name", "Gỡ quyền" to "Remove access",
        "Chưa có quyền chia sẻ riêng." to "No individual sharing permissions.", "Drive của tôi" to "My Drive",
        "Di chuyển vào đây" to "Move here", "Upload thô" to "Upload directly", "Upload dưới dạng album" to "Upload as album",
        "Ảnh và video trong thư mục được tải trực tiếp vào thư viện Photos, không tạo album." to "Photos and videos in folders are uploaded directly to the Photos library without creating an album.",
        "Mỗi thư mục gốc được chọn sẽ tạo một album cùng tên; ảnh và video bên trong được đưa vào album đó." to "Each selected root folder creates an album with the same name, containing its photos and videos.",
        "Thông tin" to "Details", "Loại" to "Type", "Không xác định" to "Unknown", "Kích thước" to "Size", "Sửa đổi" to "Modified",
        "Liên kết" to "Link", "Chủ sở hữu" to "Owner", "Người nhận xét" to "Commenter",
        "Thông báo" to "Notifications", "Cài đặt thông báo" to "Notification settings", "Giao diện" to "Appearance",
        "Chọn giao diện" to "Choose theme", "Chế độ mặc định của hệ thống" to "Use system default", "Sáng" to "Light", "Tối" to "Dark",
        "Dùng nền đen thuần khi giao diện tối đang bật" to "Use a pure black background in dark mode",
        "Bộ nhớ đệm của tài liệu" to "Document cache", "Xóa bộ nhớ đệm" to "Clear cache",
        "Xóa danh sách tệp và dữ liệu đã lưu trong bộ nhớ đệm" to "Clear cached file lists and data", "Mặc định hệ thống" to "System default",
        "Sao chép thông báo" to "Copy message", "Nội dung" to "Content", "Đang lưu…" to "Saving…", "Lưu thay đổi" to "Save changes",
        "Sửa" to "Edit", "Tự động chuyển bài" to "Autoplay next", "Lùi 10 giây" to "Rewind 10 seconds", "Tiến 10 giây" to "Forward 10 seconds",
        "Tạm dừng" to "Pause", "Phát" to "Play", "Toàn màn hình" to "Full screen", "Thoát toàn màn hình" to "Exit full screen",
        "Đóng trình phát" to "Close player", "Thu nhỏ trình phát" to "Minimize player", "Tiến độ phát" to "Playback progress",
        "Cài đặt phát" to "Playback settings", "Không thể xem loại tệp này." to "This file type cannot be previewed.",
        "Tài khoản sẽ được gỡ khỏi danh sách đã lưu trong ứng dụng. Tệp trên đám mây vẫn được giữ nguyên." to
            "The account will be removed from the app. Your cloud files will not be changed.",
        "Đã lưu thay đổi." to "Changes saved.", "Đã đăng xuất." to "Signed out.",
        "Đang thêm vào danh sách tải xuống…" to "Adding to the download queue…",
        "Nội dung vượt giới hạn 4 MB." to "Content exceeds the 4 MB limit.",
        "Tệp text quá lớn để sửa trực tiếp (giới hạn 4 MB)." to "This text file is too large to edit (4 MB limit).",
        "Đóng trình xem" to "Close viewer", "này" to "this type", "+10 giây" to "+10 seconds", "−10 giây" to "−10 seconds"
    )

    fun translate(vietnamese: String, locale: Locale): String {
        if (locale.language.equals("vi", ignoreCase = true)) return vietnamese
        english[vietnamese]?.let { return it }
        // Dynamic messages contain names/counts. Translate their stable parts
        // after checking the complete-string catalogue above.
        val translated = replacements.fold(vietnamese) { value, (vi, en) -> value.replace(vi, en) }
        // Never leak the source language on unsupported locales. This also
        // protects newly-added copy until it receives a reviewed translation.
        return if (vietnameseCharacters.containsMatchIn(translated)) "An unexpected error occurred." else translated
    }

    private val replacements = listOf(
        "Không thể" to "Unable to", "Không có" to "No", "Không tìm thấy" to "Could not find",
        "Không đọc được" to "Could not read", "Không hợp lệ" to "is invalid", "Đã hủy" to "Cancelled",
        "Đã tải" to "Uploaded", "Đã hoàn tất" to "Completed", "Đã xong" to "Completed",
        "Đang tải lên" to "Uploading", "Tải lên hoàn tất" to "Upload complete", "Tải lên chưa hoàn tất" to "Upload incomplete",
        "Đăng xuất khỏi" to "Sign out of", "mục đã chọn" to "items selected", "Di chuyển" to "Move",
        "tệp" to "files", "mục" to "items", "lỗi" to "failed", "Tài khoản" to "Account",
        "Cần quyền" to "Permission required", "Cần cấp quyền" to "Authorization required",
        "Hãy thử lại sau" to "Please try again later", "Hãy kiểm tra" to "Check",
        "đã chọn" to "selected", "Chia sẻ" to "Share", "Sắp xếp:" to "Sort:",
        "giây" to "seconds", "gần đây" to "recently", "Không tên" to "Untitled", "Thư mục" to "Folder"
    )

    private val vietnameseCharacters = Regex("[À-ỹ]")
}
