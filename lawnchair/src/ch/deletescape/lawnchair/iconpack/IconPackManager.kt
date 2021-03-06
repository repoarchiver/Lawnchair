/*
 *     This file is part of Lawnchair Launcher.
 *
 *     Lawnchair Launcher is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Lawnchair Launcher is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with Lawnchair Launcher.  If not, see <https://www.gnu.org/licenses/>.
 */

package ch.deletescape.lawnchair.iconpack

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Handler
import android.text.TextUtils
import ch.deletescape.lawnchair.lawnchairPrefs
import ch.deletescape.lawnchair.override.AppInfoProvider
import ch.deletescape.lawnchair.override.CustomInfoProvider
import ch.deletescape.lawnchair.reloadIcons
import ch.deletescape.lawnchair.runOnMainThread
import com.android.launcher3.*
import com.android.launcher3.shortcuts.ShortcutInfoCompat
import com.android.launcher3.util.ComponentKey
import java.util.*
import kotlin.collections.HashMap

class IconPackManager(private val context: Context) {

    val prefs = context.lawnchairPrefs
    private val appInfoProvider = AppInfoProvider.getInstance(context)
    val defaultPack = DefaultPack(context)
    var dayOfMonth = 0
        set(value) {
            if (value != field) {
                field = value
                onDateChanged()
            }
        }

    val packList = IconPackList(context, this)

    private val listeners: MutableSet<() -> Unit> = mutableSetOf()

    init {
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                dayOfMonth = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
            }
        }, IntentFilter(Intent.ACTION_DATE_CHANGED).apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            if (!Utilities.ATLEAST_NOUGAT) {
                addAction(Intent.ACTION_TIME_TICK)
            }
        }, null, Handler(LauncherModel.getWorkerLooper()))
    }

    private fun onDateChanged() {
        packList.onDateChanged()
    }

    private fun getIconPackInternal(name: String, put: Boolean = true, load: Boolean = false): IconPack? {
        if (name == defaultPack.packPackageName) return defaultPack
        return if (isPackProvider(context, name)) {
            packList.getPack(name, put).apply {
                if (load) {
                    ensureInitialLoadComplete()
                }
            }
        } else null
    }

    fun getIconPack(name: String, put: Boolean = true, load: Boolean = false): IconPack {
        return getIconPackInternal(name, put, load)!!
    }

    fun getIcon(launcherActivityInfo: LauncherActivityInfo,
                iconDpi: Int, flattenDrawable: Boolean, itemInfo: ItemInfo?,
                iconProvider: LawnchairIconProvider?): Drawable {
        val customEntry = CustomInfoProvider.forItem<ItemInfo>(context, itemInfo)?.getIcon(itemInfo!!)
                ?: appInfoProvider.getCustomIconEntry(launcherActivityInfo)
        val customPack = customEntry?.run {
            getIconPackInternal(packPackageName)
        }
        if (customPack != null) {
            customPack.getIcon(launcherActivityInfo, iconDpi,
                    flattenDrawable, customEntry, iconProvider)?.let { icon -> return icon }
        }
        packList.iterator().forEach { pack ->
            pack.getIcon(launcherActivityInfo, iconDpi,
                    flattenDrawable, null, iconProvider)?.let { return it }
        }
        return defaultPack.getIcon(launcherActivityInfo, iconDpi, flattenDrawable, null, iconProvider)
    }

    fun getIcon(shortcutInfo: ShortcutInfoCompat, iconDpi: Int): Drawable? {
        packList.iterator().forEach { pack ->
            pack.getIcon(shortcutInfo, iconDpi)?.let { return it }
        }
        return defaultPack.getIcon(shortcutInfo, iconDpi)
    }

    fun newIcon(icon: Bitmap, itemInfo: ItemInfo, drawableFactory: LawnchairDrawableFactory): FastBitmapDrawable {
        val key = itemInfo.targetComponent?.let { ComponentKey(it, itemInfo.user) }
        val customEntry = CustomInfoProvider.forItem<ItemInfo>(context, itemInfo)?.getIcon(itemInfo)
                ?: key?.let { appInfoProvider.getCustomIconEntry(it) }
        val customPack = customEntry?.run { getIconPackInternal(packPackageName) }
        if (customPack != null) {
            customPack.newIcon(icon, itemInfo, customEntry, drawableFactory)?.let { return it }
        }
        packList.iterator().forEach { pack ->
            pack.newIcon(icon, itemInfo, customEntry, drawableFactory)?.let { return it }
        }
        return defaultPack.newIcon(icon, itemInfo, customEntry, drawableFactory)
    }

    fun maskSupported(): Boolean = packList.appliedPacks.any { it.supportsMasking() }

    fun getEntryForComponent(component: ComponentKey): IconPack.Entry? {
        packList.iterator().forEach {
            val entry = it.getEntryForComponent(component)
            if (entry != null) return entry
        }
        return defaultPack.getEntryForComponent(component)
    }

    fun getPackProviders(): Set<String> {
        val pm = context.packageManager
        val packs = HashSet<String>()
        ICON_INTENTS.forEach { intent -> pm.queryIntentActivities(Intent(intent), PackageManager.GET_META_DATA).forEach {
            packs.add(it.activityInfo.packageName)
        } }
        return packs
    }

    fun getPackProviderInfos(): HashMap<String, IconPackInfo> {
        val pm = context.packageManager
        val packs = HashMap<String, IconPackInfo>()
        ICON_INTENTS.forEach { intent -> pm.queryIntentActivities(Intent(intent), PackageManager.GET_META_DATA).forEach {
            packs[it.activityInfo.packageName] = IconPackInfo.fromResolveInfo(it, pm)
        } }
        return packs
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
        if (!packList.appliedPacks.isEmpty()) {
            listener.invoke()
        }
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove (listener)
    }

    fun onPacksUpdated() {
        reloadIcons(context)
        runOnMainThread { listeners.forEach { it.invoke() } }
    }

    data class CustomIconEntry(val packPackageName: String, val icon: String? = null) {

        fun toPackString(): String {
            return "$packPackageName/"
        }

        override fun toString(): String {
            return "$packPackageName/${icon ?: ""}"
        }

        companion object {
            fun fromString(string: String): CustomIconEntry {
                return fromNullableString(string)!!
            }

            fun fromNullableString(string: String?): CustomIconEntry? {
                if (string == null) return null
                val parts = string.split("/")
                val icon = TextUtils.join("/", parts.subList(1, parts.size))
                return CustomIconEntry(parts[0], if (TextUtils.isEmpty(icon)) null else icon)
            }
        }
    }

    data class IconPackInfo(val packageName: String, val icon: Drawable, val label: CharSequence) {
        companion object {
            fun fromResolveInfo(info: ResolveInfo, pm: PackageManager) = IconPackInfo(info.activityInfo.packageName, info.loadIcon(pm), info.loadLabel(pm))
        }
    }

    interface OnPackChangeListener {
        fun onPackChanged()
    }

    companion object {

        const val TAG = "IconPackManager"

        @SuppressLint("StaticFieldLeak")
        private var INSTANCE: IconPackManager? = null

        fun getInstance(context: Context): IconPackManager {
            if (INSTANCE == null) {
                INSTANCE = IconPackManager(context.applicationContext)
            }
            return INSTANCE!!
        }

        val ICON_INTENTS = arrayOf(
                "com.fede.launcher.THEME_ICONPACK",
                "com.anddoes.launcher.THEME",
                "com.teslacoilsw.launcher.THEME",
                "com.gau.go.launcherex.theme",
                "org.adw.launcher.THEMES",
                "net.oneplus.launcher.icons.ACTION_PICK_ICON",
                "ch.deletescape.lawnchair.ICONPACK")

        internal fun isPackProvider(context: Context, packageName: String?): Boolean {
            if (packageName != null && !packageName.isEmpty()) {
                return ICON_INTENTS.firstOrNull { context.packageManager.queryIntentActivities(
                        Intent(it).setPackage(packageName), PackageManager.GET_META_DATA).iterator().hasNext() } != null
            }
            return false
        }
    }
}
