# TCL 55Q10G Pro: launcher follow-up, 2026-10-26

Follow-up on the same physical unit as the original report:

| Field | Observed value |
| --- | --- |
| Retail model | TCL 55Q10G Pro |
| Device / product | `tcl_mt5879_cn` / `tcl_mt5879_cn_db` |
| SoC | MediaTek MT9952 |
| Android / SDK | 11 / 30 |
| Build / incremental | `RQ3A.210705.001` / `2026012200` |
| Userspace ABIs | `armeabi-v7a,armeabi` |
| Replacement Home | `com.gathin.tvhome`, versionName `2.7`, versionCode `8` |

The earlier report's conclusion that a third-party launcher cannot be installed was too
strong. The normal ADB install path remains blocked on this firmware, but a separate,
owner-authorized installation succeeded. The stock install policy was restored and its
configuration string read back exactly after each installation. This follow-up records
results; it does not add an install-restriction workaround to the project's tools or prompt.

## Observed results

- CleanYourTV was built from the upstream source checkout used for this session. The APK
  declares HOME, has minSdk 26 and contains no native libraries.
- Explicitly starting `com.gathin.tvhome/.MainActivity` returned `Status: ok`.
- After selecting it as default Home, an ADB-injected Home key returned to that activity.
  The owner subsequently confirmed that the physical remote's Home key consistently and
  quickly returns to CleanYourTV while different signal sources and apps are in the foreground.
- With `stock_home_redirect` set to `com.tcl.cyberui` and `HomeKeyService` enabled, the focused
  window after one `adb reboot` was `com.gathin.tvhome/.MainActivity`. Default HOME resolution
  also named that component.
- The owner separately tested a power-disconnected cold start: CyberUI appears first, then
  CleanYourTV takes over automatically after approximately five seconds. This is the owner's
  estimate of the visible stock-Home interval, not a measurement of total boot time. CyberUI
  did not reappear during subsequent use in the reported tests.
- `APP_AUTO_START` and `APP_ASSOC_START` were set to allow and written to disk before reboot;
  both remained allowed afterwards. This firmware does not recognize the name `AUTO_START`.
  No comparison with these grants absent, or without writing them to disk, was performed.
- `HomeKeyService` was bound after reboot and after standby/wake.
- Standby was initially entered through ADB; the owner confirmed successful wake using the
  physical remote. ADB reconnected, and the focused window was still CleanYourTV. In a later
  owner-operated sleep/wake test, CleanYourTV appeared immediately on wake, without the
  stock-Home interval seen during a cold start.
- No launcher entries were found in the inspected DropBox output. This is a limited check,
  not a claim that every log buffer was examined or that no future crashes can occur.
- Plain `adb install` of Moonlight 12.1 returned `INSTALL_FAILED_VERIFICATION_FAILURE`.
  After a separate installation it opened `com.limelight/.PcView`; an injected Home key
  returned to CleanYourTV. The owner subsequently confirmed that Moonlight appears in the
  new launcher's app list and launches and works normally. No separate measurements of
  streaming performance or confirmation of specific controller features were recorded.
- The owner confirmed that the original system Settings interface and the functions they
  exercised work normally when using CleanYourTV.

## Keep the stock Home available

`com.tcl.cyberui` remained enabled throughout. We did not test disabling it on this unit.
Other TCL firmware can launch its stock Home explicitly during boot; retaining it also
preserves a fallback. The successful default-Home and redirect checks do not imply that
removing or disabling CyberUI is safe.

## Limits

This is one TV and one firmware, checked in the same session with an ADB reboot, an
owner-performed power-disconnected cold start, and standby/wake checks. Physical remote,
application use and Settings results above are attributed to the owner; this is not a
long-term stability study or an exhaustive check of every input, app or system function.
No firmware downgrade, root, partition modification or change to the debloat package list
was performed. Installation was observed separately from Home selection, boot behavior and
application functionality. These results do not establish compatibility with other sets
sharing the platform identifier.
