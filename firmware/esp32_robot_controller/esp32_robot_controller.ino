#include "BluetoothSerial.h"
#include <Wire.h>
#include "Adafruit_PWMServoDriver.h"

BluetoothSerial SerialBT;

#define PCA9685_ADDRESS 0x40
Adafruit_PWMServoDriver pwm = Adafruit_PWMServoDriver(PCA9685_ADDRESS);

// ================== MOTOR XE ==================
// BTS sau trai
#define BTS1_RPWM 25
#define BTS1_LPWM 26

// BTS sau phai
#define BTS2_RPWM 27
#define BTS2_LPWM 14

// TB truoc trai
#define TB1_PWM 32
#define TB1_IN1 33
#define TB1_IN2 4

// TB truoc phai
#define TB2_PWM 13
#define TB2_IN1 12
#define TB2_IN2 15

int FRONT_SPEED = 180;
int REAR_SPEED  = 90;

// ================== CANH TAY PCA9685 ==================
#define SERVO_MIN 110
#define SERVO_MAX 510

#define SERVO_BASE            0   // CH0: de, van co code dieu khien nhung co khi dang bo qua
#define SERVO_SHOULDER_MAIN   1   // CH1: servo vai chinh
#define SERVO_ELBOW           2   // CH2: khuyu
#define SERVO_WRIST           3   // CH3: co tay
#define SERVO_GRIPPER         4   // CH4: kep
#define SERVO_SHOULDER_MIRROR 5   // CH5: servo vai doi xung, goc dao 180 - goc

#define BASE_START_ANGLE      180
#define SHOULDER_START_ANGLE  90
#define ELBOW_START_ANGLE     90
#define WRIST_START_ANGLE     90
#define GRIPPER_START_ANGLE   90

// Giao thuc Bluetooth/USB:
// Xe:   F, B, L, R, S
// Servo: A/V/C/D/E + goc + '\n'
//   A180 -> base CH0
//   V90  -> shoulder CH1 va CH5 doi xung
//   C90  -> elbow CH2
//   D90  -> wrist CH3
//   E90  -> gripper CH4
// Lenh chan doan:
//   I -> quet I2C
//   X -> test servo, giu base o 180 de tranh banh rang mon
String btBuffer = "";
String usbBuffer = "";
unsigned long lastBtServoCharAt = 0;
unsigned long lastUsbServoCharAt = 0;
const unsigned long SERVO_COMMAND_TIMEOUT_MS = 250;
bool pca9685Found = false;

int angleToPulse(int angle) {
  angle = constrain(angle, 0, 180);
  return map(angle, 0, 180, SERVO_MIN, SERVO_MAX);
}

void writeServo(uint8_t channel, int angle) {
  if (!pca9685Found) {
    Serial.println("PCA9685 offline: servo command ignored");
    return;
  }

  pwm.setPWM(channel, 0, angleToPulse(angle));
}

void moveBase(int angle) {
  writeServo(SERVO_BASE, angle);
}

void moveShoulder(int angle) {
  angle = constrain(angle, 0, 180);
  writeServo(SERVO_SHOULDER_MAIN, angle);
  writeServo(SERVO_SHOULDER_MIRROR, 180 - angle);

  Serial.print("Shoulder main=");
  Serial.print(angle);
  Serial.print(" mirror=");
  Serial.println(180 - angle);
}

void setup() {
  Serial.begin(115200);
  SerialBT.begin("ESP32_ROBOT");
  Serial.println();
  Serial.println("ESP32_ROBOT v3 + 5DOF arm starting...");
  