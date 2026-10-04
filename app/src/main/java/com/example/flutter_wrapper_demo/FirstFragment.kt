package com.example.flutter_wrapper_demo

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import com.clevertap.android.sdk.CTWebInterface
import com.clevertap.android.sdk.CleverTapAPI
import com.example.flutter_wrapper_demo.databinding.FragmentFirstBinding

class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null

    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root

    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        with(binding.webview) {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            webViewClient = WebViewClient()

            // The page resolves the Android bridge as
            // window.__CT_WEBVIEW_BRIDGE__ || REACT_APP_CT_WEBVIEW_BRIDGE || "LumenWebView",
            // then requires window.<name>.pushEvent to be a function. Without this the page
            // silently falls back to the CleverTap Web SDK, which attributes login and events
            // to a web device ID instead of this app's CleverTap ID.
            CTInterfaceNames.forEach { name ->
                addJavascriptInterface(
                    CTWebInterface(CleverTapAPI.getDefaultInstance(requireContext())),
                    name
                )
            }

            if (savedInstanceState == null) {
                loadUrl(WEB_URL)
            } else {
                restoreState(savedInstanceState)
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (binding.webview.canGoBack()) {
                        binding.webview.goBack()
                    } else {
                        isEnabled = false
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        )
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // This can run after onDestroyView (e.g. when the activity is recreated while
        // this fragment sits on the back stack), so the binding may already be gone.
        _binding?.webview?.saveState(outState)
    }

    override fun onDestroyView() {
        _binding?.webview?.stopLoading()
        _binding?.webview?.destroy()
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val WEB_URL = "https://react-web-34c8f.web.app/"
        val CTInterfaceNames = arrayOf("LumenWebView", "com_example_flutter_wrapper_demo")
    }
}
