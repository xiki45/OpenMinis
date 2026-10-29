package android.net

/**
 * A concrete android.net.Uri for InputAttachment (whose uri is non-null): the
 * unit-test android.jar only has the abstract class, whose constructor is
 * package-private — hence this test-only file in android.net. Never read by
 * the code under test — attachments are opened through the injected openStream.
 */
class CtxFakeUri(private val id: String) : Uri() {
    override fun buildUpon(): Builder? = null
    override fun getAuthority(): String? = null
    override fun getEncodedAuthority(): String? = null
    override fun getEncodedFragment(): String? = null
    override fun getEncodedPath(): String? = id
    override fun getEncodedQuery(): String? = null
    override fun getEncodedSchemeSpecificPart(): String? = id
    override fun getEncodedUserInfo(): String? = null
    override fun getFragment(): String? = null
    override fun getHost(): String? = null
    override fun getLastPathSegment(): String? = id
    override fun getPath(): String? = id
    override fun getPathSegments(): MutableList<String> = mutableListOf(id)
    override fun getPort(): Int = -1
    override fun getQuery(): String? = null
    override fun getScheme(): String? = "fake"
    override fun getSchemeSpecificPart(): String? = id
    override fun getUserInfo(): String? = null
    override fun isHierarchical(): Boolean = false
    override fun isRelative(): Boolean = false
    override fun toString(): String = "fake:$id"
    override fun writeToParcel(dest: android.os.Parcel, flags: Int) {}
    override fun describeContents(): Int = 0
}
