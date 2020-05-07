# CardScan UI

This repository provides user interfaces for the CardScan library. [CardScan](https://cardscan.io/) is a relatively small library (1.9 MB) that provides fast and accurate payment card scanning.

CardScan is the foundation for CardVerify enterprise libraries, which validate the authenticity of payment cards as they are scanned.

![CardScan](docs/images/demo.gif)

## Contents

* [Requirements](#requirements)
* [Demo](#demo)
* [Installation](#installation)
* [Using CardScan](#using-cardscan-ui)
* [Customizing](#customizing-cardscan)
* [Developing](#developing-cardscan)
* [Authors](#authors)
* [License](#license)

## Requirements

* Android API level 21 or higher
* AndroidX compatibility
* Kotlin coroutine compatibility

Note: Your app does not have to be written in kotlin to integrate cardscan, but must be able to depend on kotlin functionality.

## Demo

An app demonstrating the basic capabilities of CardScan is available in [github](https://github.com/getbouncer/cardscan-demo-android).

## Installation

The CardScan libraries are published in the [jcenter](https://jcenter.bintray.com/com/getbouncer/) repository, so for most gradle configurations you only need to add the dependencies to your app's `build.gradle` file:

```gradle
dependencies {
    implementation 'com.getbouncer:cardscan-base:2.0.0003'
    implementation 'com.getbouncer:cardscan-camera:2.0.0003'
    implementation 'com.getbouncer:cardscan-ui:2.0.0003'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-core:1.3.3'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.3.3'
}
```

## Using cardscan-ui

CardScan provides a user interface through which payment cards can be scanned.

```kotlin
class MyActivity : Activity {

    /**
     * This method should be called as soon in the application as possible to give time for
     * the SDK to warm up ML model processing.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        CardScanActivity.warmUp(this)
    }

    /**
     * This method launches the CardScan SDK.
     */
    private fun onScanCardClicked() {
        CardScanActivity.start(
            activity = this,
            apiKey = "<YOUR_API_KEY_HERE>",
            enableEnterCardManually = true
        )
    }
    
    /**
     * This method receives the result from the CardScan SDK.
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent) {
        if (CardScanActivity.isScanResult(requestCode)) {
            if (resultCode == Activity.RESULT_OK) {
                handleCardScanSuccess(CardScanActivity.getScannedCard(data))
            } else if (resultCode == Activity.RESULT_CANCELED) {
                handleCardScanCanceled(data.getIntExtra(RESULT_CANCELED_REASON, -1))
            }
        }
    }
    
    private fun handleCardScanSuccess(result: ScanResult) {
        // do something with the scanned credit card
    }
    
    private fun handleCardScanCanceled(reason: Int) = when (reason) {
        CANCELED_REASON_USER -> handleUserCanceled()
        CANCELED_REASON_ENTER_MANUALLY -> handleEnterCardManually()
        CANCELED_REASON_CAMERA_ERROR -> handleCameraError()
        else -> handleCardScanFailed()
    }
    
    private fun handleUserCanceled() {
        // do something when the user cancels the card scan
    }
    
    private fun handleEnterCardManually() {
        // do something when the user wants to enter a card manually
    }
    
    private fun handleCameraError() {
        // do something when camera had an error
    }
    
    private fun handleCardScanFailed() {
        // do something when scanning a card failed
    }
}
```

## Customizing CardScan

CardScan is built to be customized to fit your UI.

### Basic modifications

To modify text, colors, or padding of the default UI, see the [customization](docs/customize.md) documentation.

### Extensive modifications

To modify arrangement or UI functionality, CardScan can be used as a library for your custom implementation. See examples in the [cardscan-base-android](https://github.com/getbouncer/cardscan-base-android) repository.

## Developing CardScan

See the [development docs](docs/develop.md) for details on developing for CardScan.

## Authors

Adam Wushensky, Sam King, and Zain ul Abi Din

## License

CardScan is available under paid and free licenses. See the [LICENSE](LICENSE) file for the full license text.

### Quick summary
In short, CardScan will remain free forever for non-commercial applications, but use by commercial applications is limited to 90 days, after which time a licensing agreement is required. We're also adding some legal liability protections.

After this period commercial applications need to convert to a licensing agreement to continue to use CardScan.
* Details of licensing (pricing, etc) are available at [https://cardscan.io/pricing](https://cardscan.io/pricing), or you can contact us at [license@getbouncer.com](mailto:license@getbouncer.com).

### More detailed summary
What's allowed under the license:
* Free use for any app for 90 days (for demos, evaluations, hackathons, etc).
* Contributions (contributors must agree to the [Contributor License Agreement](Contributor%20License%20Agreement))
* Any modifications as needed to work in your app

What's not allowed under the license:
* Commercial applications using the license for longer than 90 days without a license agreement. 
* Using us now in a commercial app today? No worries! Just email [license@getbouncer.com](mailto:license@getbouncer.com) and we’ll get you set up.
* Redistribution under a different license
* Removing attribution
* Modifying logos
* Indemnification: using this free software is ‘at your own risk’, so you can’t sue Bouncer Technologies, Inc. for problems caused by this library

Questions? Concerns? Please email us at [license@getbouncer.com](mailto:license@getbouncer.com) or ask us on [slack](https://getbouncer.slack.com).
