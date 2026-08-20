# Privacy Policy for Zezes

**Last Updated**: August 20, 2026

**Zezes** ("the Application", "we", "us", or "our"), developed by **killindodo**, is a mobile ISO flashing and bootable USB creation tool for Android devices. We are committed to protecting your privacy.

---

### 1. Information Collection and Use

**Zezes does not collect, transmit, sell, or store any personal information or analytics.**

* **No Personal Data Collected**: We do not collect names, email addresses, phone numbers, location data, or device identifiers.
* **No Third-Party Analytics / Tracking**: The Application contains zero tracking SDKs, advertising networks, or third-party telemetric services.
* **Offline Operation**: All core functionalities—including ISO file reading, partition table creation, file system extraction, and USB write operations—are performed strictly locally on your Android device.

---

### 2. Device Permissions and Usage

To perform its technical functions, Zezes requests the following standard Android permissions:

* **USB Host Access (`android.hardware.usb.host`)**:
  * **Purpose**: Required to communicate with connected USB Mass Storage devices (flash drives, card readers, external SSDs) via USB OTG.
  * **Scope**: Communication is limited to the storage volume you explicitly select to format or flash.
* **Storage Access (`android.permission.READ_EXTERNAL_STORAGE` / Storage Access Framework)**:
  * **Purpose**: Required to open and read local ISO disk image files selected by the user via the system file picker.
  * **Scope**: Only files explicitly picked by the user are read. No personal documents, photos, or data are accessed.
* **Foreground Service (`android.permission.FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_DATA_SYNC`)**:
  * **Purpose**: Required to keep disk writing operations active in the background and prevent Android from terminating the process during large file transfers.
* **Notifications (`android.permission.POST_NOTIFICATIONS`)**:
  * **Purpose**: Displays a live progress notification during active flashing/extraction tasks.

---

### 3. Data Retention and Security

* No user files, ISO content, or partition structures are uploaded or stored externally.
* All USB transfer buffers are held ephemerally in device RAM during active flashing and discarded immediately upon completion.

---

### 4. Children's Privacy

The Application does not address anyone under the age of 13. We do not knowingly collect personal identifiable information from children.

---

### 5. Changes to This Privacy Policy

We may update our Privacy Policy from time to time. Any changes will be posted on this page with an updated date.

---

### 6. Contact Us

If you have questions or suggestions regarding this Privacy Policy, please contact the developer via GitHub:
* **GitHub**: [https://github.com/killindodo/Zezes](https://github.com/killindodo/Zezes)
