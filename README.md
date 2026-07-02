# Robot Control Project

This project is an **Android application for controlling a robot arm and robot chassis** over Bluetooth, together with an **ESP32 Arduino firmware** for the robot controller.

## Overview

- The Android app provides the user interface for sending movement commands.
- The ESP32 firmware receives Bluetooth commands and drives:
  - the robot chassis motors
  - the robot arm servos through a PCA9685 driver
- The communication protocol uses simple command characters for motion and servo angles.

## Project Structure

- `app/` - Android application source code
- `firmware/esp32_robot_controller/` - ESP32 Arduino firmware for the robot
- `reference/` - reference images and documentation assets

## Firmware

The Arduino sketch imported into this project is:

- `firmware/esp32_robot_controller/ESP32_ROBOT_dieukhienv5.ino`

This sketch is designed for an ESP32-based robot controller using BluetoothSerial and a PCA9685 servo driver.

## Main Features

- Bluetooth control from Android
- Forward, backward, left, right, and stop commands for the chassis
- Servo angle control for the robot arm
- I2C scan and servo self-test commands
- Support for a multi-servo arm with mirrored shoulder movement

## Requirements

- Android Studio for the mobile app
- ESP32 board support in the Arduino IDE
- Required Arduino libraries, including:
  - `BluetoothSerial`
  - `Wire`
  - `Adafruit_PWMServoDriver`

## How to Use

### Android App
1. Open the project in Android Studio.
2. Build and run the app on an Android device.
3. Pair/connect to the ESP32 Bluetooth device named `ESP32_ROBOT`.
4. Use the on-screen controls to send robot commands.

### ESP32 Firmware
1. Open `firmware/esp32_robot_controller/ESP32_ROBOT_dieukhienv5.ino` in the Arduino IDE.
2. Select the correct ESP32 board and port.
3. Install the required libraries if needed.
4. Upload the sketch to the ESP32.

## Notes

- The firmware sketch included in this project uses a Bluetooth command protocol for both the chassis and the arm.
- If you change motor pins, servo channels, or command mappings, update both the firmware and the Android app accordingly.

