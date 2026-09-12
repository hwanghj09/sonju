package com.hwanghj09.sonju.agent

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

data class InstalledApp(val label: String, val packageName: String)

object InstalledApps {
    // Samsung Internet 30 gates its virtual web tree behind its own service allowlist.
    // Use the installed native-accessibility browser for an unnamed, new web task only.
    fun preferredWebBrowser(apps: List<InstalledApp>): InstalledApp? =
        apps.firstOrNull { it.packageName == "com.android.chrome" }

    fun query(packageManager: PackageManager): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, 0)
        }
        return activities.mapNotNull { info ->
            val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
            val label = info.loadLabel(packageManager)?.toString()?.trim().orEmpty()
            label.takeIf(String::isNotBlank)?.let { InstalledApp(it, packageName) }
        }.distinctBy(InstalledApp::packageName).sortedBy(InstalledApp::packageName)
    }

    fun plannerContext(apps: List<InstalledApp>?): String = if (apps == null) {
        "설치 앱 목록을 확인하지 못했다. 현재 화면에 없다는 이유로 미설치라고 단정하지 않는다."
    } else {
        "Android에서 확인한 실행 가능한 설치 앱 ${apps.size}개 (label, package):\n" +
            JSONArray(apps.map { app ->
                JSONObject().put("label", app.label).put("package", app.packageName)
            }).toString() + preferredWebBrowser(apps)?.let {
                "\n새 웹 탐색에서 브라우저를 지정하지 않았다면 접근성 본문을 제공하는 ${it.packageName}을 우선한다. " +
                    "이미 열린 페이지를 읽거나 조작하라는 요청과 특정 브라우저를 지정한 요청은 현재/지정 앱에서 수행한다."
            }.orEmpty()
    }
}
