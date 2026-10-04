package com.example.flutter_wrapper_demo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.clevertap.android.sdk.CTInboxListener
import com.clevertap.android.sdk.CTInboxStyleConfig
import com.clevertap.android.sdk.CleverTapAPI
import com.example.flutter_wrapper_demo.databinding.FragmentInboxBinding

class InboxFragment : Fragment(), CTInboxListener {

    private var _binding: FragmentInboxBinding? = null

    private val binding get() = _binding!!

    private val cleverTap: CleverTapAPI?
        get() = CleverTapAPI.getDefaultInstance(requireContext())

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentInboxBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.buttonOpenInbox.setOnClickListener {
            cleverTap?.showAppInbox(buildStyleConfig())
        }

        cleverTap?.apply {
            ctNotificationInboxListener = this@InboxFragment
            // Safe to call repeatedly - the SDK ignores subsequent calls once ready.
            initializeInbox()
        } ?: showInitialisationFailed()
    }

    override fun inboxDidInitialize() {
        onMainThread {
            refreshCounts()
            _binding?.buttonOpenInbox?.isEnabled = true
        }
    }

    override fun inboxMessagesDidUpdate() {
        onMainThread { refreshCounts() }
    }

    /**
     * CleverTap delivers [CTInboxListener] callbacks on a background thread, so any
     * view updates have to be marshalled onto the main thread.
     */
    private fun onMainThread(action: () -> Unit) {
        val root = _binding?.root ?: return
        root.post { if (_binding != null) action() }
    }

    override fun onResume() {
        super.onResume()
        if (_binding?.buttonOpenInbox?.isEnabled == true) {
            refreshCounts()
        }
    }

    override fun onDestroyView() {
        cleverTap?.ctNotificationInboxListener = null
        _binding = null
        super.onDestroyView()
    }

    private fun refreshCounts() {
        val binding = _binding ?: return
        val cleverTap = cleverTap ?: return showInitialisationFailed()

        val unread = cleverTap.inboxMessageUnreadCount
        val total = cleverTap.inboxMessageCount

        binding.textviewInboxUnread.text = if (unread > 0) {
            getString(R.string.inbox_unread_count, unread)
        } else {
            getString(R.string.inbox_no_unread)
        }
        binding.textviewInboxSummary.text = getString(R.string.inbox_total_count, total)
    }

    private fun showInitialisationFailed() {
        val binding = _binding ?: return
        binding.textviewInboxUnread.text = getString(R.string.inbox_no_unread)
        binding.textviewInboxSummary.text = getString(R.string.inbox_failed)
        binding.buttonOpenInbox.isEnabled = false
    }

    private fun buildStyleConfig() = CTInboxStyleConfig().apply {
        // firstTabTitle is the always-present unfiltered "All messages" tab; each entry in
        // `tabs` adds an extra tab filtered by that string as a CleverTap campaign tag.
        firstTabTitle = getString(R.string.inbox_tab_all)
        tabs = arrayListOf(
            getString(R.string.inbox_tab_promotions),
            getString(R.string.inbox_tab_offers)
        )
        navBarTitle = getString(R.string.inbox_nav_title)
    }
}