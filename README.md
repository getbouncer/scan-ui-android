# Overview

This repository provides user interfaces for scanning cards. [CardScan](https://cardscan.io/) is a relatively small library (1.9 MB) that provides fast and accurate payment card scanning.

This library is the foundation for CardScan and CardVerify enterprise libraries, which validate the authenticity of payment cards as they are scanned.

![demo](docs/images/demo.gif)

## Contents

* [Requirements](#requirements)
* [Demo](#demo)
* [Installation](#installation)
* [Using this library](#using-this-library)
* [Customizing](#customizing)
* [Developing](#developing)
* [Authors](#authors)
* [License](#license)

## Requirements

* Android API level 21 or higher
* AndroidX compatibility
* Kotlin coroutine compatibility

Note: Your app does not have to be written in kotlin to integrate this library, but must be able to depend on kotlin functionality.

## Demo

An app demonstrating the basic capabilities of this library is available in [github](https://github.com/getbouncer/cardscan-demo-android).

## Installation

These libraries are published in the [jcenter](https://jcenter.bintray.com/com/getbouncer/) repository, so for most gradle configurations you only need to add the dependencies to your app's `build.gradle` file:

```gradle
dependencies {
    implementation 'com.getbouncer:scan-framework:2.0.0011'
    implementation 'com.getbouncer:scan-camera:2.0.0011'
    implementation 'com.getbouncer:scan-ui:2.0.0011'
}
```

## Using this library

This library provides a user interface through which cards (payment, driver license, etc.) can be scanned.

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

## Customizing

This library is built to be customized to fit your UI.

### Basic modifications

To modify text, colors, or padding of the default UI, see the [customization](docs/customize.md) documentation.

### Extensive modifications

To modify arrangement or UI functionality, you can create a custom implementation based on this library. See examples in the [scan-framework](https://github.com/getbouncer/scan-framework-android) repository.

## Developing

See the [development docs](docs/develop.md) for details on developing this library.

## Authors

Adam Wushensky, Sam King, and Zain ul Abi Din

## License

This library is available under paid and free licenses. See the [LICENSE](LICENSE) file for the full license text.

### Quick summary
In short, this library will remain free forever for non-commercial applications, but use by commercial applications is limited to 90 days, after which time a licensing agreement is required. We're also adding some legal liability protections.

After this period commercial applications need to convert to a licensing agreement to continue to use this library.
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
