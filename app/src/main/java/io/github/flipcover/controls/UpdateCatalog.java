package io.github.flipcover.controls;

import org.json.JSONObject;
import java.net.URI;

/** The server's schema-1 APK catalog; paths stay inside the configured project. */
record UpdateCatalog(String packageName, long versionCode, String versionName, int minSdk, String changelog, URI apkUri, long apkSize, String sha256) {
    static UpdateCatalog parse(String encoded, AppConfig.Update config, String expectedPackage) throws Exception {
        JSONObject source = new JSONObject(encoded);
        AppConfig.integer(source, "schemaVersion", 1, 1);
        String name = AppConfig.text(source, "packageName");
        if (!name.equals(expectedPackage)) throw new IllegalArgumentException("更新包名与本应用不一致");
        long version = AppConfig.integer(source, "versionCode", 1, Integer.MAX_VALUE);
        String label = AppConfig.text(source, "versionName");
        if (label.isBlank() || label.length() > 128) throw new IllegalArgumentException("更新版本名称无效");
        int minimum = (int) AppConfig.integer(source, "minSdk", 1, 10000);
        String hash = AppConfig.text(source, "sha256");
        if (!hash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("更新文件校验值无效");
        URI relative = new URI(AppConfig.text(source, "apkPath"));
        if (relative.isAbsolute() || relative.getRawAuthority() != null || relative.getRawQuery() != null || relative.getRawFragment() != null || relative.getPath() == null || relative.getPath().startsWith("/") || !relative.getPath().endsWith(".apk")) throw new IllegalArgumentException("APK 路径必须是项目内的相对路径");
        safePath(relative);
        URI download = config.catalogUri().resolve(relative);
        inProject(config.catalogUri(), download);
        return new UpdateCatalog(name, version, label, minimum, AppConfig.text(source, "changelog"), download, AppConfig.integer(source, "apkSize", 1, config.maxApkBytes()), hash);
    }
    static URI catalogUri(String address) throws Exception {
        URI uri = new URI(address);
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getPath() == null || !uri.getPath().endsWith("/catalog.json")) throw new IllegalArgumentException("更新目录地址无效");
        safePath(uri); return uri;
    }
    static void inProject(URI catalog, URI candidate) throws Exception {
        if (!catalog.getScheme().equals(candidate.getScheme()) || !catalog.getHost().equalsIgnoreCase(candidate.getHost() == null ? "" : candidate.getHost()) || catalog.getPort() != candidate.getPort() || candidate.getUserInfo() != null || candidate.getRawQuery() != null || candidate.getRawFragment() != null) throw new IllegalArgumentException("更新地址超出配置的服务器");
        safePath(candidate);
        if (!candidate.getPath().startsWith(catalog.resolve(".").getPath())) throw new IllegalArgumentException("更新地址超出项目目录");
    }
    private static void safePath(URI uri) {
        String path = uri.getPath();
        if (path == null || path.contains("\\") || path.contains("%") || path.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("更新路径无效");
        for (String segment : path.split("/")) if (segment.equals(".") || segment.equals("..")) throw new IllegalArgumentException("更新路径不能越界");
    }
}
