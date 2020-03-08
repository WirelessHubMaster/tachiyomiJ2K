package eu.kanade.tachiyomi.ui.manga

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.IconCompat
import androidx.palette.graphics.Palette
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.ChangeBounds
import androidx.transition.ChangeImageTransform
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import com.afollestad.materialdialogs.MaterialDialog
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.ControllerChangeType
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.bumptech.glide.signature.ObjectKey
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.database.models.MangaImpl
import eu.kanade.tachiyomi.data.download.DownloadService
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.glide.GlideApp
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.base.controller.BaseController
import eu.kanade.tachiyomi.ui.base.controller.DialogController
import eu.kanade.tachiyomi.ui.base.controller.NoToolbarElevationController
import eu.kanade.tachiyomi.ui.catalogue.CatalogueController
import eu.kanade.tachiyomi.ui.library.ChangeMangaCategoriesDialog
import eu.kanade.tachiyomi.ui.library.LibraryController
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.main.SearchActivity
import eu.kanade.tachiyomi.ui.manga.MangaController.Companion.FROM_CATALOGUE_EXTRA
import eu.kanade.tachiyomi.ui.manga.chapter.ChapterItem
import eu.kanade.tachiyomi.ui.manga.chapter.ChapterMatHolder
import eu.kanade.tachiyomi.ui.manga.chapter.ChaptersAdapter
import eu.kanade.tachiyomi.ui.manga.chapter.DownloadCustomChaptersDialog
import eu.kanade.tachiyomi.ui.manga.info.EditMangaDialog
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.security.SecureActivityDelegate
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.launchUI
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.doOnApplyWindowInsets
import eu.kanade.tachiyomi.util.view.getText
import eu.kanade.tachiyomi.util.view.snack
import eu.kanade.tachiyomi.util.view.updateLayoutParams
import eu.kanade.tachiyomi.util.view.updatePaddingRelative
import jp.wasabeef.glide.transformations.CropSquareTransformation
import jp.wasabeef.glide.transformations.MaskTransformation
import kotlinx.android.synthetic.main.main_activity.*
import kotlinx.android.synthetic.main.manga_details_controller.*
import kotlinx.android.synthetic.main.manga_header_item.*
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

class MangaDetailsController : BaseController,
    FlexibleAdapter.OnItemClickListener,
    FlexibleAdapter.OnItemLongClickListener,
    ChaptersAdapter.MangaHeaderInterface,
    ChangeMangaCategoriesDialog.Listener,
    DownloadCustomChaptersDialog.Listener,
    NoToolbarElevationController {

    constructor(manga: Manga?,
        fromCatalogue: Boolean = false,
        smartSearchConfig: CatalogueController.SmartSearchConfig? = null,
        update: Boolean = false) : super(Bundle().apply {
        putLong(MangaController.MANGA_EXTRA, manga?.id ?: 0)
        putBoolean(FROM_CATALOGUE_EXTRA, fromCatalogue)
        putParcelable(MangaController.SMART_SEARCH_CONFIG_EXTRA, smartSearchConfig)
        putBoolean(MangaController.UPDATE_EXTRA, update)
    }) {
        this.manga = manga
        if (manga != null) {
            source = Injekt.get<SourceManager>().getOrStub(manga.source)
        }
    }

    constructor(mangaId: Long) : this(
        Injekt.get<DatabaseHelper>().getManga(mangaId).executeAsBlocking())

    constructor(bundle: Bundle) : this(bundle.getLong(MangaController.MANGA_EXTRA)) {
        val notificationId = bundle.getInt("notificationId", -1)
        val context = applicationContext ?: return
        if (notificationId > -1) NotificationReceiver.dismissNotification(
            context, notificationId, bundle.getInt("groupId", 0)
        )
    }

    private var manga: Manga? = null
    private var source: Source? = null
    var colorAnimator:ValueAnimator? = null
    lateinit var presenter:MangaDetailsPresenter
    var coverColor:Int? = null
    var toolbarIsColored = false
    private var snack: Snackbar? = null
    val fromCatalogue = args.getBoolean(FROM_CATALOGUE_EXTRA, false)
    var coverDrawable:Drawable? = null
    /**
     * Adapter containing a list of chapters.
     */
    private var adapter: ChaptersAdapter? = null

    // Hold a reference to the current animator,
    // so that it can be canceled mid-way.
    private var currentAnimator: Animator? = null

    var headerHeight = 0

    init {
        setHasOptionsMenu(true)
    }

    override fun getTitle(): String? {
        return if (toolbarIsColored) manga?.currentTitle() else null
    }

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)
        coverColor = null
        if (!::presenter.isInitialized) presenter = MangaDetailsPresenter(this, manga!!, source!!)

        // Init RecyclerView and adapter
        adapter = ChaptersAdapter(this, view.context)

        recycler.adapter = adapter
        recycler.layoutManager = LinearLayoutManager(view.context)
        recycler.addItemDecoration(
            DividerItemDecoration(
                view.context,
                DividerItemDecoration.VERTICAL
            )
        )
        recycler.setHasFixedSize(false)
        adapter?.fastScroller = fast_scroller
        val attrsArray = intArrayOf(android.R.attr.actionBarSize)
        val array = view.context.obtainStyledAttributes(attrsArray)
        val appbarHeight = array.getDimensionPixelSize(0, 0)
        array.recycle()
        val offset = 10.dpToPx

        recycler.doOnApplyWindowInsets { v, insets, _ ->
            headerHeight = appbarHeight + insets.systemWindowInsetTop
            swipe_refresh.setProgressViewOffset(false, (-40).dpToPx, headerHeight + offset)
            (recycler.findViewHolderForAdapterPosition(0) as? MangaHeaderHolder)
                ?.setTopHeight(headerHeight)
            fast_scroller?.updateLayoutParams<ViewGroup.MarginLayoutParams>  {
                topMargin = appbarHeight + insets.systemWindowInsetTop
                bottomMargin = insets.systemWindowInsetBottom
            }
            // offset the recycler by the fab's inset + some inset on top
            v.updatePaddingRelative(bottom = insets.systemWindowInsetBottom)
        }

        presenter.onCreate()

        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val atTop = !recycler.canScrollVertically(-1)
                if ((!atTop && !toolbarIsColored) || (atTop && toolbarIsColored)) {
                    toolbarIsColored = !atTop
                    val color =
                        coverColor ?: activity!!.getResourceColor(android.R.attr.colorPrimary)
                    val colorFrom =
                        if (colorAnimator?.isRunning == true) activity?.window?.statusBarColor
                            ?: color
                        else ColorUtils.setAlphaComponent(
                            color, if (toolbarIsColored) 0 else 175
                        )
                    val colorTo = ColorUtils.setAlphaComponent(
                        color, if (toolbarIsColored) 175 else 0
                    )
                    colorAnimator?.cancel()
                    colorAnimator = ValueAnimator.ofObject(
                        android.animation.ArgbEvaluator(), colorFrom, colorTo
                    )
                    colorAnimator?.duration = 250 // milliseconds
                    colorAnimator?.addUpdateListener { animator ->
                        (activity as MainActivity).toolbar.setBackgroundColor(animator.animatedValue as Int)
                        activity?.window?.statusBarColor = (animator.animatedValue as Int)
                    }
                    colorAnimator?.start()
                    val isCurrentController =
                        router?.backstack?.lastOrNull()?.controller() == this@MangaDetailsController
                    if (isCurrentController) setTitle()
                }
            }
        })
        setPaletteColor()

        if (manga?.initialized != true)
            swipe_refresh.post {
                swipe_refresh.isRefreshing = true
            }

        swipe_refresh.setOnRefreshListener {
            presenter.refreshAll()
        }
    }

    private fun setPaletteColor() {
        val view = view ?: return
        GlideApp.with(view.context).load(manga)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .signature(ObjectKey(MangaImpl.getLastCoverFetch(manga!!.id!!).toString()))
            .into(object : CustomTarget<Drawable>() {
                override fun onResourceReady(resource: Drawable,
                    transition: Transition<in Drawable>?
                ) {
                    coverDrawable = resource
                    Palette.from(
                        (resource as BitmapDrawable).bitmap).generate {
                        if (recycler == null) return@generate
                        val currentNightMode =
                            recycler.resources!!.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                        val colorBack = view.context.getResourceColor(
                            android.R.attr.colorBackground
                        )
                        val backDropColor =
                            (if (currentNightMode == Configuration.UI_MODE_NIGHT_NO) it?.getLightMutedColor(
                                colorBack
                            )
                            else it?.getDarkMutedColor(colorBack)) ?: colorBack
                        coverColor = backDropColor
                        (recycler.findViewHolderForAdapterPosition(0) as? MangaHeaderHolder)
                            ?.setBackDrop(backDropColor)
                        if (toolbarIsColored) {
                            val translucentColor = ColorUtils.setAlphaComponent(backDropColor, 175)
                            (activity as MainActivity).toolbar.setBackgroundColor(translucentColor)
                            activity?.window?.statusBarColor = translucentColor
                        }
                    }
                }

                override fun onLoadCleared(placeholder: Drawable?) { }
            })
    }

    override fun onActivityResumed(activity: Activity) {
        super.onActivityResumed(activity)
        presenter.isLockedFromSearch = SecureActivityDelegate.shouldBeLocked()
        presenter.headerItem.isLocked = presenter.isLockedFromSearch
        presenter.fetchChapters()
    }

    fun showError(message: String) {
        swipe_refresh?.isRefreshing = presenter.isLoading
        view?.snack(message)
    }

    fun updateChapterDownload(download: Download) {
        getHolder(download.chapter)?.notifyStatus(download.status, presenter.isLockedFromSearch,
            download.progress)
    }

    private fun getHolder(chapter: Chapter): ChapterMatHolder? {
        return recycler?.findViewHolderForItemId(chapter.id!!) as? ChapterMatHolder
    }

    override fun onChangeStarted(handler: ControllerChangeHandler, type: ControllerChangeType) {
        super.onChangeStarted(handler, type)
        if (type == ControllerChangeType.PUSH_ENTER || type == ControllerChangeType.POP_ENTER) {
            if (type == ControllerChangeType.POP_ENTER)
                return
            (activity as MainActivity).appbar.setBackgroundColor(Color.TRANSPARENT)
            (activity as MainActivity).toolbar.setBackgroundColor(Color.TRANSPARENT)
            activity?.window?.statusBarColor = Color.TRANSPARENT
        }
        else if (type == ControllerChangeType.PUSH_EXIT || type == ControllerChangeType.POP_EXIT) {
            if (router.backstack.lastOrNull()?.controller() is DialogController)
                return
            if (type == ControllerChangeType.POP_EXIT) setHasOptionsMenu(false)
            colorAnimator?.cancel()

            val colorPrimary = activity?.getResourceColor(
                android.R.attr.colorBackground
            ) ?: Color.BLACK
            (activity as MainActivity).appbar.setBackgroundColor(colorPrimary)
            (activity as MainActivity).toolbar.setBackgroundColor(colorPrimary)

            activity?.window?.statusBarColor = ColorUtils.setAlphaComponent(colorPrimary, 175)
        }
    }

    fun setRefresh(enabled: Boolean) {
        swipe_refresh.isRefreshing = enabled
    }

    fun updateHeader() {
        if (presenter.chapters.isEmpty()) {
            adapter?.updateDataSet(listOf(presenter.headerItem))
        }
        else {
            swipe_refresh?.isRefreshing = presenter.isLoading
            adapter?.updateDataSet(
                listOf(ChapterItem(presenter.headerItem, presenter.manga)) + presenter.chapters
            )
        }
        activity?.invalidateOptionsMenu()
    }


    fun updateChapters(chapters: List<ChapterItem>) {
        swipe_refresh?.isRefreshing = presenter.isLoading
        if (presenter.chapters.isEmpty() && fromCatalogue && !presenter.hasRequested) {
            launchUI { swipe_refresh?.isRefreshing = true }
            presenter.fetchChaptersFromSource()
        }
        adapter?.updateDataSet(listOf(presenter.headerItem) + chapters)
        activity?.invalidateOptionsMenu()
    }

    fun refreshAdapter() = adapter?.notifyDataSetChanged()

    override fun onItemClick(view: View?, position: Int): Boolean {
        val chapter = adapter?.getItem(position)?.chapter ?: return false
        if (chapter.isHeader) return false
        openChapter(chapter)
        return false
    }

    override fun onItemLongClick(position: Int) {
        val adapter = adapter ?: return
        val item = adapter.getItem(position) ?: return
        val itemView = getHolder(item)?.itemView ?: return
        val popup = PopupMenu(itemView.context, itemView, Gravity.END)

        // Inflate our menu resource into the PopupMenu's Menu
        popup.menuInflater.inflate(R.menu.chapters_mat_single, popup.menu)

        // Hide bookmark if bookmark
        popup.menu.findItem(R.id.action_bookmark).isVisible = !item.bookmark
        popup.menu.findItem(R.id.action_remove_bookmark).isVisible = item.bookmark

        // Hide mark as unread when the chapter is unread
        if (!item.read && item.last_page_read == 0) {
            popup.menu.findItem(R.id.action_mark_as_unread).isVisible = false
        }

        // Hide mark as read when the chapter is read
        if (item.read) {
            popup.menu.findItem(R.id.action_mark_as_read).isVisible = false
        }

        // Set a listener so we are notified if a menu item is clicked
        popup.setOnMenuItemClickListener { menuItem ->
            val chapters = listOf(item)
            when (menuItem.itemId) {
                R.id.action_bookmark -> bookmarkChapters(chapters, true)
                R.id.action_remove_bookmark -> bookmarkChapters(chapters, false)
                R.id.action_mark_as_read -> markAsRead(chapters)
                R.id.action_mark_previous_as_read -> markPreviousAsRead(item)
                R.id.action_mark_as_unread -> markAsUnread(chapters)
            }
            true
        }

        // Finally show the PopupMenu
        popup.show()
    }

    private fun markPreviousAsRead(chapter: ChapterItem) {
        val adapter = adapter ?: return
        val chapters = if (presenter.sortDescending()) adapter.items.reversed() else adapter.items
        val chapterPos = chapters.indexOf(chapter)
        if (chapterPos != -1) {
            markAsRead(chapters.take(chapterPos))
        }
    }

    private fun bookmarkChapters(chapters: List<ChapterItem>, bookmarked: Boolean) {
        //destroyActionModeIfNeeded()
        presenter.bookmarkChapters(chapters, bookmarked)
    }

    private fun markAsRead(chapters: List<ChapterItem>) {
        presenter.markChaptersRead(chapters, true)
        if (presenter.preferences.removeAfterMarkedAsRead()) {
            presenter.deleteChapters(chapters)
        }
    }

    private fun markAsUnread(chapters: List<ChapterItem>) {
        presenter.markChaptersRead(chapters, false)
    }

    private fun openChapter(chapter: Chapter, hasAnimation: Boolean = false) {
        val activity = activity ?: return
        val intent = ReaderActivity.newIntent(activity, manga!!, chapter)
        if (hasAnimation) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        startActivity(intent)
    }

    override fun onDestroyView(view: View) {
        snack?.dismiss()
        presenter.onDestroy()
        super.onDestroyView(view)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.manga_details, menu)
        val editItem = menu.findItem(R.id.action_edit)
        editItem.isVisible = presenter.manga.favorite && !presenter.isLockedFromSearch
        menu.findItem(R.id.action_download).isVisible = !presenter.isLockedFromSearch
        menu.findItem(R.id.action_mark_all_as_read).isVisible =
            presenter.getNextUnreadChapter() != null && !presenter.isLockedFromSearch
        menu.findItem(R.id.action_mark_all_as_unread).isVisible =
            !presenter.allUnread() && !presenter.isLockedFromSearch
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_edit -> EditMangaDialog(this, presenter.manga).showDialog(router)
            R.id.action_open_in_web_view -> openInWebView()
            R.id.action_share -> prepareToShareManga()
            R.id.action_add_to_home_screen -> addToHomeScreen()
            R.id.action_mark_all_as_read -> {
                MaterialDialog(view!!.context)
                    .message(R.string.mark_all_as_read_message)
                    .positiveButton(R.string.action_mark_as_read) {
                        markAsRead(presenter.chapters)
                    }
                    .negativeButton(android.R.string.cancel)
                    .show()
            }
            R.id.action_mark_all_as_unread -> markAsUnread(presenter.chapters)
            R.id.download_next, R.id.download_next_5, R.id.download_next_10,
            R.id.download_custom, R.id.download_unread, R.id.download_all
            -> downloadChapters(item.itemId)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    /**
     * Called to run Intent with [Intent.ACTION_SEND], which show share dialog.
     */
    override fun prepareToShareManga() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && coverDrawable != null)
            GlideApp.with(activity!!).asBitmap().load(presenter.manga).into(object :
                CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    presenter.shareManga(resource)
                }
                override fun onLoadCleared(placeholder: Drawable?) {}

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    shareManga()
                }
            })
        else shareManga()
    }

    /**
     * Called to run Intent with [Intent.ACTION_SEND], which show share dialog.
     */
    fun shareManga(cover: File? = null) {
        val context = view?.context ?: return

        val source = presenter.source as? HttpSource ?: return
        val stream = cover?.getUriCompat(context)
        try {
            val url = source.mangaDetailsRequest(presenter.manga).url.toString()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/*"
                putExtra(Intent.EXTRA_TEXT, url)
                putExtra(Intent.EXTRA_TITLE, presenter.manga.currentTitle())
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                if (stream != null) {
                    clipData = ClipData.newRawUri(null, stream)
                }
            }
            startActivity(Intent.createChooser(intent, context.getString(R.string.action_share)))
        } catch (e: Exception) {
            context.toast(e.message)
        }
    }

    override fun openInWebView() {
        val source = presenter.source as? HttpSource ?: return

        val url = try {
            source.mangaDetailsRequest(presenter.manga).url.toString()
        } catch (e: Exception) {
            return
        }

        val activity = activity ?: return
        val intent = WebViewActivity.newIntent(activity.applicationContext, source.id, url, presenter.manga
            .originalTitle())
        startActivity(intent)
    }

    private fun downloadChapters(choice: Int) {
        val chaptersToDownload = when (choice) {
            R.id.download_next -> presenter.getUnreadChaptersSorted().take(1)
            R.id.download_next_5 -> presenter.getUnreadChaptersSorted().take(5)
            R.id.download_next_10 -> presenter.getUnreadChaptersSorted().take(10)
            R.id.download_custom -> {
                showCustomDownloadDialog()
                return
            }
            R.id.download_unread -> presenter.chapters.filter { !it.read }
            R.id.download_all -> presenter.chapters
            else -> emptyList()
        }
        if (chaptersToDownload.isNotEmpty()) {
            downloadChapters(chaptersToDownload)
        }
    }

    private fun downloadChapters(chapters: List<ChapterItem>) {
        val view = view
        presenter.downloadChapters(chapters)
        if (view != null && !presenter.manga.favorite && (snack == null ||
                snack?.getText() != view.context.getString(R.string.snack_add_to_library))) {
            snack = view.snack(view.context.getString(R.string.snack_add_to_library), Snackbar.LENGTH_INDEFINITE) {
                setAction(R.string.action_add) {
                    presenter.setFavorite(true)
                }
                addCallback(object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
                    override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                        super.onDismissed(transientBottomBar, event)
                        if (snack == transientBottomBar) snack = null
                    }
                })
            }
            (activity as? MainActivity)?.setUndoSnackBar(snack)
        }
    }

    /**
     * Add a shortcut of the manga to the home screen
     */
    private fun addToHomeScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // TODO are transformations really unsupported or is it just the Pixel Launcher?
            createShortcutForShape()
        } else {
            ChooseShapeDialog(this).showDialog(router)
        }
    }

    /**
     * Retrieves the bitmap of the shortcut with the requested shape and calls [createShortcut] when
     * the resource is available.
     *
     * @param i The shape index to apply. Defaults to circle crop transformation.
     */
    fun createShortcutForShape(i: Int = 0) {
        if (activity == null) return
        GlideApp.with(activity!!)
            .asBitmap()
            .load(presenter.manga)
            .diskCacheStrategy(DiskCacheStrategy.NONE)
            .apply {
                when (i) {
                    0 -> circleCrop()
                    1 -> transform(RoundedCorners(5))
                    2 -> transform(CropSquareTransformation())
                    3 -> centerCrop().transform(MaskTransformation(R.drawable.mask_star))
                }
            }
            .into(object : CustomTarget<Bitmap>(128, 128) {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    createShortcut(resource)
                }

                override fun onLoadCleared(placeholder: Drawable?) { }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    activity?.toast(R.string.icon_creation_fail)
                }
            })
    }

    /**
     * Create shortcut using ShortcutManager.
     *
     * @param icon The image of the shortcut.
     */
    private fun createShortcut(icon: Bitmap) {
        val activity = activity ?: return

        // Create the shortcut intent.
        val shortcutIntent = activity.intent
            .setAction(MainActivity.SHORTCUT_MANGA)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MangaController.MANGA_EXTRA, presenter.manga.id)

        // Check if shortcut placement is supported
        if (ShortcutManagerCompat.isRequestPinShortcutSupported(activity)) {
            val shortcutId = "manga-shortcut-${presenter.manga.originalTitle()}-${presenter.source.name}"

            // Create shortcut info
            val shortcutInfo = ShortcutInfoCompat.Builder(activity, shortcutId)
                .setShortLabel(presenter.manga.currentTitle())
                .setIcon(IconCompat.createWithBitmap(icon))
                .setIntent(shortcutIntent)
                .build()

            val successCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Create the CallbackIntent.
                val intent = ShortcutManagerCompat.createShortcutResultIntent(activity, shortcutInfo)

                // Configure the intent so that the broadcast receiver gets the callback successfully.
                PendingIntent.getBroadcast(activity, 0, intent, 0)
            } else {
                NotificationReceiver.shortcutCreatedBroadcast(activity)
            }

            // Request shortcut.
            ShortcutManagerCompat.requestPinShortcut(activity, shortcutInfo,
                successCallback.intentSender)
        }
    }

    private fun showCustomDownloadDialog() {
        DownloadCustomChaptersDialog(this, presenter.chapters.size).showDialog(router)
    }

    override fun downloadCustomChapters(amount: Int) {
        val chaptersToDownload = presenter.getUnreadChaptersSorted().take(amount)
        if (chaptersToDownload.isNotEmpty()) {
            downloadChapters(chaptersToDownload)
        }
    }

    override fun inflateView(inflater: LayoutInflater, container: ViewGroup): View {
        return inflater.inflate(R.layout.manga_details_controller, container, false)
    }

    override fun coverColor(): Int? = coverColor
    override fun topCoverHeight(): Int = headerHeight

    override fun readNextChapter() {
        if (activity is SearchActivity && presenter.isLockedFromSearch) {
            SecureActivityDelegate.promptLockIfNeeded(activity)
            return
        }
        val item = presenter.getNextUnreadChapter()
        if (item != null) {
                openChapter(item.chapter)
        } else if (snack == null || snack?.getText() != view?.context?.getString(
                R.string.no_next_chapter)) {
            snack = view?.snack(R.string.no_next_chapter, Snackbar.LENGTH_LONG) {
                addCallback(object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
                    override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                        super.onDismissed(transientBottomBar, event)
                        if (snack == transientBottomBar) snack = null
                    }
                })
            }
        }
    }

    override fun downloadChapter(position: Int) {
        val view = view ?: return
        val chapter = adapter?.getItem(position) ?: return
        if (chapter.isHeader) return
        if (chapter.status != Download.NOT_DOWNLOADED && chapter.status != Download.ERROR) {
            presenter.deleteChapters(listOf(chapter))
        }
        else {
            if (chapter.status == Download.ERROR)
                DownloadService.start(view.context)
            else
                downloadChapters(listOf(chapter))
        }
    }

    override fun tagClicked(text: String) {
        val firstController = router.backstack.first()?.controller()
        if (firstController is LibraryController && router.backstack.size == 2) {
            router.handleBack()
            firstController.search(text)
        }
    }

    override fun showChapterFilter() {
        ChaptersSortBottomSheet(this).show()
    }

    override fun favoriteManga(longPress: Boolean) {
        if (presenter.isLockedFromSearch) {
            SecureActivityDelegate.promptLockIfNeeded(activity)
            return
        }
        val manga = presenter.manga
        if (longPress) {
            if (!manga.favorite) {
                presenter.toggleFavorite()
                showAddedSnack()
            }
            val categories = presenter.getCategories()
            if (categories.isEmpty()) {
                // no categories exist, display a message about adding categories
                snack = view?.snack(R.string.action_add_category)
            } else {
                val ids = presenter.getMangaCategoryIds(manga)
                val preselected = ids.mapNotNull { id ->
                    categories.indexOfFirst { it.id == id }.takeIf { it != -1 }
                }.toTypedArray()

                ChangeMangaCategoriesDialog(this, listOf(manga), categories, preselected)
                    .showDialog(router)
            }
        }
        else {
            if (!manga.favorite) {
                toggleMangaFavorite()
            }
            else {
                val headerHolder = recycler.findViewHolderForAdapterPosition(0) as? MangaHeaderHolder ?:
                return
                val popup = PopupMenu(view!!.context, headerHolder.favorite_button)
                popup.menu.add(R.string.remove_from_library)

                // Set a listener so we are notified if a menu item is clicked
                popup.setOnMenuItemClickListener {
                    toggleMangaFavorite()
                    true
                }
                popup.show()
            }
        }
    }

    private fun toggleMangaFavorite() {
        val manga = presenter.manga
        if (presenter.toggleFavorite()) {
            val categories = presenter.getCategories()
            val defaultCategoryId = presenter.preferences.defaultCategory()
            val defaultCategory = categories.find { it.id == defaultCategoryId }
            when {
                defaultCategory != null -> presenter.moveMangaToCategory(manga, defaultCategory)
                defaultCategoryId == 0 || categories.isEmpty() -> // 'Default' or no category
                    presenter.moveMangaToCategory(manga, null)
                else -> {
                    val ids = presenter.getMangaCategoryIds(manga)
                    val preselected = ids.mapNotNull { id ->
                        categories.indexOfFirst { it.id == id }.takeIf { it != -1 }
                    }.toTypedArray()

                    ChangeMangaCategoriesDialog(
                        this,
                        listOf(manga),
                        categories,
                        preselected
                    ).showDialog(router)
                }
            }
            showAddedSnack()
        } else {
            showRemovedSnack()
        }
    }

    private fun showAddedSnack() {
        val view = view ?: return
        snack?.dismiss()
        snack = view.snack(view.context.getString(R.string.manga_added_library))
    }

    private fun showRemovedSnack() {
        val view = view ?: return
        snack?.dismiss()
        snack = view.snack(
            view.context.getString(R.string.manga_removed_library),
            Snackbar.LENGTH_INDEFINITE
        ) {
            setAction(R.string.action_undo) {
                presenter.setFavorite(true)
            }
            addCallback(object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    super.onDismissed(transientBottomBar, event)
                    if (!presenter.manga.favorite) presenter.confirmDeletion()
                }
            })
        }
        val favButton = (recycler.findViewHolderForAdapterPosition(0)
            as? MangaHeaderHolder)?.favorite_button
        (activity as? MainActivity)?.setUndoSnackBar(snack, favButton)
    }

    override fun mangaPresenter(): MangaDetailsPresenter = presenter

    override fun updateCategoriesForMangas(mangas: List<Manga>, categories: List<Category>) {
        val manga = mangas.firstOrNull() ?: return
        presenter.moveMangaToCategories(manga, categories)
    }

    /**
     * Copies a string to clipboard
     *
     * @param content the actual text to copy to the board
     * @param label Label to show to the user describing the content
     */
    override fun copyToClipboard(content: String, label: Int) {
        if (content.isBlank()) return

        val activity = activity ?: return
        val view = view ?: return

        val contentType = view.context.getString(label)
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(contentType, content))

        snack = view.snack(view.context.getString(R.string.copied_to_clipboard, contentType))
    }

    override fun handleBack(): Boolean {
        if (manga_cover_full?.visibility == View.VISIBLE)
        {
            manga_cover_full?.performClick()
            return true
        }
        return super.handleBack()
    }

    override fun zoomImageFromThumb(thumbView: View) {
        // If there's an animation in progress, cancel it immediately and proceed with this one.
        currentAnimator?.cancel()

        // Load the high-resolution "zoomed-in" image.
        val expandedImageView = manga_cover_full ?: return
        val fullBackdrop = full_backdrop
        val image = coverDrawable ?: return
        expandedImageView.setImageDrawable(image)

        // Hide the thumbnail and show the zoomed-in view. When the animation
        // begins, it will position the zoomed-in view in the place of the
        // thumbnail.
        thumbView.alpha = 0f
        expandedImageView.visibility = View.VISIBLE
        fullBackdrop.visibility = View.VISIBLE

        // Set the pivot point to 0 to match thumbnail

        swipe_refresh.isEnabled = false

        val rect = Rect()
        thumbView.getGlobalVisibleRect(rect)
        expandedImageView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            height = thumbView.height
            width = thumbView.width
            topMargin = rect.top
            leftMargin = rect.left
            rightMargin = rect.right
            bottomMargin = rect.bottom
        }
        expandedImageView.requestLayout()

        expandedImageView.post {
            val defMargin = 16.dpToPx
            expandedImageView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                height = ViewGroup.LayoutParams.MATCH_PARENT
                width = ViewGroup.LayoutParams.MATCH_PARENT
                topMargin = defMargin + headerHeight
                leftMargin = defMargin
                rightMargin = defMargin
                bottomMargin = defMargin + recycler.paddingBottom
            }
            val shortAnimationDuration = resources?.getInteger(
                android.R.integer.config_shortAnimTime
            ) ?: 0

            // TransitionSet for the full cover because using animation for this SUCKS
            val transitionSet = TransitionSet()
            val bound = ChangeBounds()
            transitionSet.addTransition(bound)
            val changeImageTransform = ChangeImageTransform()
            transitionSet.addTransition(changeImageTransform)
            transitionSet.duration = shortAnimationDuration.toLong()
            TransitionManager.beginDelayedTransition(frame_layout, transitionSet)

            // AnimationSet for backdrop because idk how to use TransitionSet
            currentAnimator = AnimatorSet().apply {
                play(
                    ObjectAnimator.ofFloat(fullBackdrop, View.ALPHA, 0f, 0.5f)
                )
                duration = shortAnimationDuration.toLong()
                interpolator = DecelerateInterpolator()
                addListener(object : AnimatorListenerAdapter() {

                    override fun onAnimationEnd(animation: Animator) {
                        TransitionManager.endTransitions(frame_layout)
                        currentAnimator = null
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        TransitionManager.endTransitions(frame_layout)
                        currentAnimator = null
                    }
                })
                start()
            }

            expandedImageView.setOnClickListener {
                currentAnimator?.cancel()

                val rect = Rect()
                thumbView.getGlobalVisibleRect(rect)
                expandedImageView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    height = thumbView.height
                    width = thumbView.width
                    topMargin = rect.top
                    leftMargin = rect.left
                    rightMargin = rect.right
                    bottomMargin = rect.bottom
                }

                // Zoom out back to tc thumbnail
                val transitionSet = TransitionSet()
                val bound = ChangeBounds()
                transitionSet.addTransition(bound)
                val changeImageTransform = ChangeImageTransform()
                transitionSet.addTransition(changeImageTransform)
                transitionSet.duration = shortAnimationDuration.toLong()
                TransitionManager.beginDelayedTransition(frame_layout, transitionSet)

                // Animation to remove backdrop and hide the full cover
                currentAnimator = AnimatorSet().apply {
                    play(ObjectAnimator.ofFloat(fullBackdrop, View.ALPHA, 0f))
                    duration = shortAnimationDuration.toLong()
                    interpolator = DecelerateInterpolator()
                    addListener(object : AnimatorListenerAdapter() {

                        override fun onAnimationEnd(animation: Animator) {
                            thumbView.alpha = 1f
                            expandedImageView.visibility = View.GONE
                            fullBackdrop.visibility = View.GONE
                            swipe_refresh.isEnabled = true
                            currentAnimator = null
                        }

                        override fun onAnimationCancel(animation: Animator) {
                            thumbView.alpha = 1f
                            expandedImageView.visibility = View.GONE
                            fullBackdrop.visibility = View.GONE
                            swipe_refresh.isEnabled = true
                            currentAnimator = null
                        }
                    })
                    start()
                }
            }
        }
    }
}