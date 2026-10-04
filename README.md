# CleverTap WebView Integration

How the storefront page loaded inside the Android app reports **profile logins** and **events** into the app's CleverTap project — and what the website must do to make that happen.

---

## 1. The problem this solves

A page running inside a `WebView` is a **web context**, so by default anything it records goes to CleverTap's **Web SDK** and is attributed to a *web* device ID. The app's own CleverTap profile (the one that receives push notifications and App Inbox messages) is a *different* identity.

To connect the two, we expose CleverTap's Android SDK to the page as a **JavaScript bridge**. The page then calls native Android methods directly, and every login/event lands in the **app's** CleverTap profile.

---

## 2. How it works

```
┌─────────────────────────────┐        ┌──────────────────────────────┐
│  Website (JS)               │        │  Android app (Kotlin)        │
│                             │        │                              │
│  window.LumenWebView        │        │  CTWebInterface              │
│    .onUserLogin(json)  ─────┼───────>│    .onUserLogin(json)        │
│    .pushEvent(n, json) ─────┼───────>│    .pushEvent(n, json)       │
└─────────────────────────────┘        │        │                     │
                                       │        v                     │
                                       │  CleverTapAPI (Android SDK)   │
                                       │        │                     │
                                       └────────┼─────────────────────┘
                                                v
                                    CleverTap dashboard: Events + Profiles
```

The flow is always the same:

1. Android registers a bridge object under a **fixed name** before loading the URL.
2. That object is injected into the page as `window.<name>`.
3. The page detects it (`typeof window.<name>.pushEvent === "function"`) and switches to the **native** code path.
4. Every helper the page calls forwards straight to the native SDK.
5. CleverTap attributes the data to this device / this user in the app's project.

---

## 3. Native side — what we implemented

### 3.1 CleverTap initialisation

CleverTap is initialised by the SDK itself (no manual `CleverTapAPI.getDefaultInstance()` call needed) because the app uses CleverTap's `Application` subclass and declares its credentials in the manifest.

`app/src/main/AndroidManifest.xml`
```xml
<application
    android:name=".DemoApplication"
    ...>
    <meta-data
        android:name="CLEVERTAP_ACCOUNT_ID"
        android:value="@string/clevertap_account_id" />
    <meta-data
        android:name="CLEVERTAP_TOKEN"
        android:value="@string/clevertap_account_token" />
</application>
```

`app/src/main/java/.../DemoApplication.kt`
```kotlin
class DemoApplication : CleverTapApplication() {
    override fun onCreate() {
        CleverTapAPI.setDebugLevel(
            if (BuildConfig.DEBUG) LogLevel.VERBOSE else LogLevel.INFO
        )
        super.onCreate()
    }
}
```

> **Why `Application` subclass?** `CleverTapApplication` reads those `<meta-data>` values during `super.onCreate()`, which is where the SDK obtains your Account ID and Token. Credentials must be present **before** `super.onCreate()` runs, and log level is set before it so early SDK logs are captured.

**Log levels:** debug builds log `VERBOSE` (everything, useful for confirming events arrive); release builds log `INFO` only, so user data never leaks to logcat in production.

### 3.2 The bridge

`app/src/main/java/.../FirstFragment.kt`
```kotlin
with(binding.webview) {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    ...
    CTInterfaceNames.forEach { name ->
        addJavascriptInterface(
            CTWebInterface(CleverTapAPI.getDefaultInstance(requireContext())),
            name
        )
    }

    if (savedInstanceState == null) loadUrl(WEB_URL) else restoreState(savedInstanceState)
}

// companion object
val CTInterfaceNames = arrayOf("LumenWebView", "com_example_flutter_wrapper_demo")
```

Two things matter here:

- **`addJavascriptInterface(object, name)`** publishes a Kotlin/Java object into the page as `window.<name>`. The bridge must be registered **before** `loadUrl`, otherwise the first page load cannot see it.
- **The name is a contract.** The page decides which name to look for. `"LumenWebView"` is the name this website ships with; the package-style name is kept registered as well so a renamed package does not break the page.

`CTWebInterface` is CleverTap's own class. We do not write any bridge methods ourselves — it already wraps the calls we need.

### 3.3 Why the methods are visible to JS at all

`addJavascriptInterface` exposes a method to JavaScript **only** if it is annotated `@android.webkit.JavascriptInterface`. Since Android 4.2 (API 17) this annotation is mandatory; without it the method is invisible to the page even on the same object.

All of `CTWebInterface`'s public methods carry it (verified in the SDK bytecode):

```
RuntimeVisibleAnnotations:
  android.webkit.JavascriptInterface
```

> **Security note.** Only annotated methods are reachable, so exposing `CTWebInterface` does not leak the rest of the Android object graph. Keep the bridge limited to purpose-built SDK wrappers like this one — never pass an Activity, Fragment or Context directly.

---

## 4. The bridge contract (what the page can call)

All arguments cross the boundary as **strings**, and return values are **not** propagated back to JavaScript.

| Method | Signature | Purpose |
|---|---|---|
| `onUserLogin` | `(profileJson: String)` | Identifies the user. **Call once per login.** Also sets Identity, which is how CleverTap merges anonymous history into the profile. |
| `pushEvent` | `(eventName: String)` | Records an event with no properties. |
| `pushEvent` | `(eventName: String, propsJson: String)` | Records an event with properties. |
| `pushProfile` | `(profileJson: String)` | Updates profile fields without re-identifying (e.g. city, age, preference). |
| `pushChargedEvent` | `(detailsJson: String, itemsJson: String)` | Records a purchase with the charge breakdown and line items. |
| `incrementValue` | `(key: String, value: Double)` | Increments a multi-value counter (cart value, score). |
| `decrementValue` | `(key: String, value: Double)` | Decrements a counter. |
| `addMultiValueForKey` | `(key: String, value: String)` | Appends to a multi-value list (e.g. wishlist). |
| `setMultiValueForKey` | `(key: String, valuesJson: String)` | Replaces a multi-value list. |
| `removeValueForKey` | `(key: String)` | Clears a single profile property. |
| `getSdkVersion` | `()` → `int` | Native CleverTap SDK version. |

### Argument rules

- **Profile and event properties must be passed as JSON strings**, not objects. `JSON.stringify({...})` on the web side, `JSONObject` serialised to string on the native side.
- Pass a JSON **object** for properties. A bare number or string would be dropped.
- `onUserLogin` is the identity call. Prefer it over `pushProfile` for the first identification after sign-in, because only `onUserLogin` merges pre-login anonymous events into the profile.

---

## 5. Website side — how the page must use the bridge

The bundled helper in this site resolves the bridge and forwards calls like this (simplified from the minified bundle):

```js
const DEFAULT_BRIDGE = "LumenWebView";

function bridgeName() {
  return window.__CT_WEBVIEW_BRIDGE__
      || DEFAULT_BRIDGE;
}

// Android: an object with a pushEvent method
function androidBridge() {
  const b = window[bridgeName()];
  return b && typeof b.pushEvent === "function" ? b : null;
}

function isNative() {
  return androidBridge() !== null;
}

// Generic forwarder -> bridge[methodName](...args)
function nativeCall(method, ...args) {
  const b = androidBridge();
  if (!b) return false;
  try {
    b[method](...args);
    return true;
  } catch (err) {
    console.error(`CleverTap WebView bridge: ${method} failed`, err);
    return false;
  }
}
```

Three behaviours follow directly from this design:

1. **The name is configurable** at runtime via `window.__CT_WEBVIEW_BRIDGE__`, or at build time via `REACT_APP_CT_WEBVIEW_BRIDGE`.
2. **Detection is by capability, not by platform** — the page asks "is there an object with `pushEvent`?", so the name is the only thing that must agree between app and page.
3. **`pushEvent` must exist**, otherwise the page assumes it is running in a plain browser.

### 5.1 Triggering a login

```js
nativeCall("onUserLogin", JSON.stringify({
  Identity: "user123",
  Name: "Demo Shopper",
  Email: "demo@lumen.co"
}));
```

Then record the login itself as an event:

```js
nativeCall("pushEvent", "User Signed In", JSON.stringify({ Identity: "user123" }));
```

### 5.2 Triggering an event

With properties:
```js
nativeCall("pushEvent", "Product Viewed", JSON.stringify({
  productId: "sku-42",
  productName: "Linen Shirt",
  category: "Apparel",
  price: 49.0
}));
```

Without properties:
```js
nativeCall("pushEvent", "Cart Viewed");
```

### 5.3 Triggering a purchase

```js
nativeCall("pushChargedEvent",
  JSON.stringify({ amount: 120.0, payment: "UPI", chargedID: "ord_8812" }),
  JSON.stringify([
    { productId: "sku-42", productName: "Linen Shirt", category: "Apparel",
      price: 49.0, quantity: 1 },
    { productId: "sku-07", productName: "Canvas Tote", category: "Accessories",
      price: 30.0, quantity: 1 }
  ])
);
```

> Use `pushChargedEvent` for purchases rather than a plain event — it is what populates **Revenue** reporting in CleverTap.

### 5.4 Always guard the call

Because the page may also run in a normal desktop browser where the bridge is absent, wrap calls:

```js
if (isNative()) {
  nativeCall("pushEvent", "CTA Clicked", JSON.stringify({ ctaId: "buy-now" }));
}
```

A page that calls the bridge unconditionally throws `TypeError: Cannot read properties of undefined` in a browser.

---

## 6. The silent fallback (important)

When the bridge is missing or misnamed, this site does **not** error. It routes calls to the CleverTap **Web SDK** instead:

```js
// no native bridge -> web SDK
clevertap.event.push({ eventName: "Product Viewed", ... })
```

No exception, no visible failure — the data simply lands under a **web device ID** and never appears against the app's user. This is exactly why a bridge-name mismatch is easy to ship and hard to notice.

**Quick check that the bridge is live** — run this in the page console (or via `WebView.evaluateJavascript`):

```js
const n = window.__CT_WEBVIEW_BRIDGE__ || "LumenWebView";
({
  name: n,
  bridge: typeof window[n],              // expect "object"
  pushEvent: typeof window[n]?.pushEvent, // expect "function"
  onUserLogin: typeof window[n]?.onUserLogin
});
```

Expected: `{ name: "LumenWebView", bridge: "object", pushEvent: "function", onUserLogin: "function" }`.

---

## 7. Verifying events arrive

```bash
# 1. run the app in debug
./gradlew :app:installDebug

# 2. watch CleverTap SDK logs
adb logcat -s CleverTap:D
```

Because debug builds run at `LogLevel.VERBOSE`, you should see log lines from the SDK as the page performs logins and events. Also check **Dashboard → Events** for the event name — an event only appears there if it was created in the dashboard first.

---

## 8. Gotchas

| Symptom | Cause | Fix |
|---|---|---|
| Events show up for a web user, not the app user | Bridge name mismatch → Web SDK fallback | Match `window.<name>`; verify with the console check in §6 |
| `TypeError: Cannot read properties of undefined` in a browser | Bridge called without an `isNative()` guard | Guard all calls |
| Login appears, but pre-login events are not carried over | Used `pushProfile` instead of `onUserLogin` | Use `onUserLogin` at sign-in |
| Event visible in logcat but absent in dashboard | Event not created in the dashboard | Create the event in CleverTap first |
| Properties missing on the event | Properties not JSON-stringified | `JSON.stringify(props)` |
| Nothing works after a package rename | Bridge name hard-coded to the old package | Keep the name in a list, or inject `window.__CT_WEBVIEW_BRIDGE__` |

> **Note:** this website's Web SDK account ID (`679-R87-876Z`) differs from the Android app's account ID in the manifest. This is harmless while the native bridge is detected — the Web SDK path is never taken — but the two projects will not share users if the bridge ever breaks. Align them if a single unified profile is required.

---

## 9. Files

| File | Role |
|---|---|
| `app/src/main/AndroidManifest.xml` | `DemoApplication`, CleverTap Account ID / Token, FCM service |
| `app/src/main/java/.../DemoApplication.kt` | CleverTap `Application` subclass, debug log level |
| `app/src/main/java/.../FirstFragment.kt` | WebView setup + `addJavascriptInterface` bridge registration |
| `app/src/main/res/layout/fragment_first.xml` | WebView container |
| `app/src/main/res/navigation/nav_graph.xml` | Fragment destinations |