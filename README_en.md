# Material Printer

##### [Русский](README.md) | [English](README_en.md)

> [!WARNING]
>
> Warning: the app does not support English.

This project has no affiliation with the original WalkPrint app or Shenzhen Yihengke Electronic Technology Co. Ltd.

---

Material Printer is a convenient app for controlling thermal printers that work with the "WalkPrint" app. It was written based on the original app and the [YHK-Cat-Thermal-Printer](https://github.com/abhigkar/YHK-Cat-Thermal-Printer) script.

About the printer: the printer broadcasts the identifier "YHK-XXXX", where "XXXX" is the last four characters of its MAC address. It's unclear why, but the printer prints images and black objects with streaks, which may be a quirk or defect of my unit. As far as I can tell, these printers come in different form factors (cats, rabbits, plain ones), but they all share the same protocol. Anyway, a new one is arriving soon, so I'll check. Mine looks like this:

![Photo of the printer](printer.jpg)

About the app:

- 100% offline and private: the manifest has no `INTERNET` permission.

- Material You

- Battery level display support.

---
