#include "BluetoothSerial.h"
#include <Wire.h>
#include "Adafruit_PWMServoDriver.h"

BluetoothSerial SerialBT;

#define PCA9685_ADDRESS 0x40 //hệ số 16 (Hexadecimal)
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

int FRONT_SPEED = 180;  //max 130prm
int REAR_SPEED  = 90;   //50% 280prm

/// ================== CANH TAY PCA9685 ==================
//Gia tri do rong xung tuong ung 0-180

#define SERVO_MIN 110
#define SERVO_MAX 510

// Cac kenh PCA9685 xen ke (chan chan) de gian cach day cap
#define SERVO_BASE            0   // CH0:  de xoay
#define SERVO_SHOULDER_MAIN   2   // CH2:  vai chinh
#define SERVO_ELBOW           4   // CH4:  khuyu
#define SERVO_WRIST           6   // CH6:  co tay
#define SERVO_GRIPPER         8   // CH8:  kep
#define SERVO_SHOULDER_MIRROR 10  // CH10: vai doi xung, goc = 180 - goc chinh

#define NUM_SERVOS     11    // mang du cho index 0..10

const uint8_t SERVO_CHANNELS[] = {
  SERVO_BASE, SERVO_SHOULDER_MAIN, SERVO_ELBOW,
  SERVO_WRIST, SERVO_GRIPPER, SERVO_SHOULDER_MIRROR
};
#define NUM_SERVO_CHANNELS 6

#define BASE_START_ANGLE      90
#define SHOULDER_START_ANGLE  90
#define ELBOW_START_ANGLE     90
#define WRIST_START_ANGLE     90
#define GRIPPER_START_ANGLE   90

// ================== SERVO SMOOTHING ==================
// Tang SERVO_SPEED de nhanh hon, giam de muot hon
#define SERVO_SPEED    80   // do/giay (60=cham muot, 120=can bang, 200=nhanh)
#define SERVO_TICK_MS  20    // chu ky cap nhat interpolator (ms)

float servoCurrentAngle[NUM_SERVOS];
float servoTargetAngle[NUM_SERVOS];
bool  servoInitialized[NUM_SERVOS];  // false = chua nhan lenh, khong write hw
unsigned long lastServoTickMs = 0;

// ================== GIAO THUC BT/USB ==================
// Xe:    F, B, L, R, S
// Servo: A/V/C/D/E + goc + '\n'
//   A90  -> base CH0
//   V90  -> shoulder CH1 & CH5 doi xung
//   C90  -> elbow CH2
//   D90  -> wrist CH3
//   E90  -> gripper CH4
// Chan doan:
//   I    -> quet I2C
//   X    -> test servo

String btBuffer  = ""; //vung dem cho Bluetooth
String usbBuffer = ""; //vung dem cho usb
unsigned long lastBtServoCharAt  = 0;
unsigned long lastUsbServoCharAt = 0;
const unsigned long SERVO_COMMAND_TIMEOUT_MS = 500;  

bool pca9685Found = false;

// =====================================================
//  SERVO SMOOTHING CORE
// =====================================================

int angleToPulse(int angle) {
  angle = constrain(angle, 0, 180); //ep goc
  return map(angle, 0, 180, SERVO_MIN, SERVO_MAX);
}

// Ghi thang xuong PCA9685 (_ noi bo)
void _setPWMAngle(uint8_t ch, float angle) {
  if (!pca9685Found) return;
  angle = constrain(angle, 0.0f, 180.0f);
  pwm.setPWM(ch, 0, angleToPulse((int)angle));
}

// Dat goc dich 
void writeServo(uint8_t channel, int angle) {
  if (!pca9685Found) {
    Serial.println("PCA9685 offline: servo ignored");
    return;
  }
  angle = constrain(angle, 0, 180);
  servoTargetAngle[channel] = (float)angle;

  // Chong giat !
  if (!servoInitialized[channel]) {
    servoCurrentAngle[channel] = (float)angle;
    servoInitialized[channel]  = true;
    _setPWMAngle(channel, (float)angle);
  }
}

// Chay moi loop() — cap nhat tat ca servo theo toc do SERVO_SPEED
void tickServoSmoothing() {
  unsigned long now = millis();
  if (now - lastServoTickMs < SERVO_TICK_MS) return;

  float dt = (now - lastServoTickMs) / 1000.0f;
  lastServoTickMs = now;

  // Gioi han dt de tranh nhay lon sau khi pause/debug
  if (dt > 0.1f) dt = 0.1f;

  float maxStep = SERVO_SPEED * dt;

  for (int i = 0; i < NUM_SERVO_CHANNELS; i++) {
    uint8_t ch = SERVO_CHANNELS[i];
    if (!servoInitialized[ch]) continue;

    float diff = servoTargetAngle[ch] - servoCurrentAngle[ch];
    if (fabsf(diff) < 0.3f) continue;  // da toi dich, khoi write I2C thua

    float step = constrain(diff, -maxStep, maxStep);
    servoCurrentAngle[ch] += step;
    _setPWMAngle(ch, servoCurrentAngle[ch]);
  }
}

void initServoSmoothing() {
  for (int i = 0; i < NUM_SERVOS; i++) {
    servoCurrentAngle[i] = 90.0f;
    servoTargetAngle[i]  = 90.0f;
    servoInitialized[i]  = false;
  }
  lastServoTickMs = millis();
}

// =====================================================
//  DIEU KHIEN CANH TAY
// =====================================================
//de
void moveBase(int angle) {
  writeServo(SERVO_BASE, angle);
}
//khop vai
void moveShoulder(int angle) {
  angle = constrain(angle, 0, 180);
  writeServo(SERVO_SHOULDER_MAIN,   angle);
  writeServo(SERVO_SHOULDER_MIRROR, 180 - angle);

  Serial.print("Shoulder main=");
  Serial.print(angle);
  Serial.print(" mirror=");
  Serial.println(180 - angle);
}

// =====================================================
//  SETUP & LOOP
// =====================================================

bool isI2CDevicePresent(uint8_t address) {
  Wire.beginTransmission(address);
  return Wire.endTransmission() == 0;
}

void setup() {
  Serial.begin(115200);
  SerialBT.begin("ESP32_ROBOT");
  Serial.println();
  Serial.println("ESP32_ROBOT v4 + 5DOF arm + servo smoothing");

  Wire.begin(21, 22);  // SDA=21, SCL=22
  delay(100);
  pca9685Found = isI2CDevicePresent(PCA9685_ADDRESS);

  if (pca9685Found) {
    Serial.println("PCA9685 found at 0x40");
    pwm.begin();
    pwm.setPWMFreq(50);
    delay(10);
  } else {
    Serial.println("PCA9685 NOT found. Check SDA=21, SCL=22, VCC, GND.");
  }

  initServoSmoothing();
  Serial.println("Servo smoothing ON — khong tu dong di chuyen khi bat nguon.");
  Serial.print("SERVO_SPEED=");
  Serial.print(SERVO_SPEED);
  Serial.println(" do/giay. Tang #define SERVO_SPEED de nhanh hon.");

  pinMode(BTS1_RPWM, OUTPUT);
  pinMode(BTS1_LPWM, OUTPUT);
  pinMode(BTS2_RPWM, OUTPUT);
  pinMode(BTS2_LPWM, OUTPUT);

  pinMode(TB1_PWM, OUTPUT);
  pinMode(TB1_IN1, OUTPUT);
  pinMode(TB1_IN2, OUTPUT);

  pinMode(TB2_PWM, OUTPUT);
  pinMode(TB2_IN1, OUTPUT);
  pinMode(TB2_IN2, OUTPUT);

  stopMotors();
}

void loop() {
  tickServoSmoothing();

  clearStaleServoBuffer(btBuffer,  lastBtServoCharAt);
  clearStaleServoBuffer(usbBuffer, lastUsbServoCharAt);

  while (Serial.available()) {
    processIncomingChar(Serial.read(), usbBuffer, lastUsbServoCharAt, "USB");
  }

  while (SerialBT.available()) {
    processIncomingChar(SerialBT.read(), btBuffer, lastBtServoCharAt, "BT");
  }
}

// =====================================================
//  XU LY LENH
// =====================================================

void processIncomingChar(char c, String &buffer, unsigned long &lastCharAt, const char *source) {
  if (c == '\r') return;

  if (buffer.length() > 0) {
    receiveServoCommandChar(c, buffer, lastCharAt, source);
    return;
  }

  if (c == 'X') {
    Serial.print(source);
    Serial.println(" command: servo self-test");
    testServos();
    return;
  }

  if (c == 'I') {
    printI2CScan();
    return;
  }

  if (isMotorCommand(c)) {
    Serial.print(source);
    Serial.print(" motor command: ");
    Serial.println(c);
    handleMotorCommand(c);
    return;
  }

  if (isServoCommandStart(c)) {
    buffer = String(c);
    lastCharAt = millis();
  }
}

bool isMotorCommand(char c) {
  return c == 'F' || c == 'B' || c == 'L' || c == 'R' || c == 'S';
}

bool isServoCommandStart(char c) {
  return c == 'A' || c == 'V' || c == 'C' || c == 'D' || c == 'E';
}

void receiveServoCommandChar(char c, String &buffer, unsigned long &lastCharAt, const char *source) {
  lastCharAt = millis();

  if (c == '\n') {
    Serial.print(source);
    Serial.print(" servo packet: ");
    Serial.println(buffer);
    handleServoCommand(buffer);
    buffer = "";
    return;
  }

  if (buffer.length() >= 6) {
    buffer = "";
    return;
  }

  buffer += c;
}

void clearStaleServoBuffer(String &buffer, unsigned long lastCharAt) {
  if (buffer.length() == 0) return;
  if (millis() - lastCharAt > SERVO_COMMAND_TIMEOUT_MS) {
    buffer = "";
  }
}

void printI2CScan() {
  Serial.println("I2C scan started");
  byte found = 0;
  bool foundPca9685 = false;

  for (byte address = 1; address < 127; address++) {
    Wire.beginTransmission(address);
    byte error = Wire.endTransmission();

    if (error == 0) {
      Serial.print("I2C device found at 0x");
      if (address < 16) Serial.print("0");
      Serial.println(address, HEX);
      if (address == PCA9685_ADDRESS) foundPca9685 = true;
      found++;
    }
  }

  if (found == 0) Serial.println("No I2C devices found");

  pca9685Found = foundPca9685;
  Serial.print("PCA9685 status: ");
  Serial.println(pca9685Found ? "ONLINE at 0x40" : "OFFLINE");
  Serial.println("I2C scan finished");
}

void handleMotorCommand(char cmd) {
  if      (cmd == 'F') forward();
  else if (cmd == 'B') backward();
  else if (cmd == 'L') turnLeft();
  else if (cmd == 'R') turnRight();
  else if (cmd == 'S') stopMotors();
}

// =====================================================
//  XU LY SERVO
// =====================================================

void handleServoCommand(String cmd) {
  if (cmd.length() < 2) return;

  char   id        = cmd.charAt(0);
  String angleText = cmd.substring(1);
  if (!isNumeric(angleText)) return;

  int angle = constrain(angleText.toInt(), 0, 180);
  Serial.print("Servo target: ");
  Serial.println(cmd);

  if      (id == 'A') moveBase(angle);
  else if (id == 'V') moveShoulder(angle);
  else if (id == 'C') writeServo(SERVO_ELBOW,   angle);
  else if (id == 'D') writeServo(SERVO_WRIST,   angle);
  else if (id == 'E') writeServo(SERVO_GRIPPER, angle);
}

void testServos() {
  Serial.println("Servo self-test started");
  stopMotors();
  pca9685Found = isI2CDevicePresent(PCA9685_ADDRESS);

  if (!pca9685Found) {
    Serial.println("Servo self-test stopped: PCA9685 not detected");
    return;
  }

  // Reset current = target = 90 truoc khi test de tranh giat
  for (int i = 0; i < NUM_SERVO_CHANNELS; i++) {
    uint8_t ch = SERVO_CHANNELS[i];
    servoCurrentAngle[ch] = 90.0f;
    servoTargetAngle[ch]  = 90.0f;
    servoInitialized[ch]  = true;
    _setPWMAngle(ch, 90.0f);
}
  delay(300);

  testShoulderPair();
  testSingleServo(SERVO_ELBOW,   ELBOW_START_ANGLE);
  testSingleServo(SERVO_WRIST,   WRIST_START_ANGLE);
  testSingleServo(SERVO_GRIPPER, GRIPPER_START_ANGLE);

  Serial.println("Servo self-test finished");
}

// Test dung delay — chap nhan duoc vi dang test, khong chay smoothing
void testShoulderPair() {
  moveShoulder(60);  delay(700);
  moveShoulder(120); delay(700);
  moveShoulder(SHOULDER_START_ANGLE); delay(500);
  // Cho interpolator hoan thanh truoc khi sang servo tiep theo
  delay(200);
}

void testSingleServo(uint8_t channel, int startAngle) {
  writeServo(channel, 60);  delay(700);
  writeServo(channel, 120); delay(700);
  writeServo(channel, startAngle); delay(500);
  delay(200);
}

bool isNumeric(String text) {
  if (text.length() == 0) return false;
  for (int i = 0; i < (int)text.length(); i++) {
    if (!isDigit(text.charAt(i))) return false;
  }
  return true;
}

// =====================================================
//  DIEU KHIEN XE
// =====================================================
//REAR_SPEED xung toc do
void forward() {
  analogWrite(BTS1_RPWM, REAR_SPEED);
  analogWrite(BTS1_LPWM, 0);
  analogWrite(BTS2_RPWM, REAR_SPEED);
  analogWrite(BTS2_LPWM, 0);

  digitalWrite(TB1_IN1, HIGH);
  digitalWrite(TB1_IN2, LOW);
  analogWrite(TB1_PWM, FRONT_SPEED);

  digitalWrite(TB2_IN1, HIGH);
  digitalWrite(TB2_IN2, LOW);
  analogWrite(TB2_PWM, FRONT_SPEED);
}

void backward() {
  analogWrite(BTS1_RPWM, 0);
  analogWrite(BTS1_LPWM, REAR_SPEED);
  analogWrite(BTS2_RPWM, 0);
  analogWrite(BTS2_LPWM, REAR_SPEED);

  digitalWrite(TB1_IN1, LOW);
  digitalWrite(TB1_IN2, HIGH);
  analogWrite(TB1_PWM, FRONT_SPEED);

  digitalWrite(TB2_IN1, LOW);
  digitalWrite(TB2_IN2, HIGH);
  analogWrite(TB2_PWM, FRONT_SPEED);
}

void turnLeft() {
  analogWrite(BTS1_RPWM, 0);
  analogWrite(BTS1_LPWM, REAR_SPEED);
  analogWrite(BTS2_RPWM, REAR_SPEED);
  analogWrite(BTS2_LPWM, 0);

  digitalWrite(TB1_IN1, LOW);
  digitalWrite(TB1_IN2, HIGH);
  analogWrite(TB1_PWM, FRONT_SPEED);

  digitalWrite(TB2_IN1, HIGH);
  digitalWrite(TB2_IN2, LOW);
  analogWrite(TB2_PWM, FRONT_SPEED);
}

void turnRight() {
  analogWrite(BTS1_RPWM, REAR_SPEED);
  analogWrite(BTS1_LPWM, 0);
  analogWrite(BTS2_RPWM, 0);
  analogWrite(BTS2_LPWM, REAR_SPEED);

  digitalWrite(TB1_IN1, HIGH);
  digitalWrite(TB1_IN2, LOW);
  analogWrite(TB1_PWM, FRONT_SPEED);

  digitalWrite(TB2_IN1, LOW);
  digitalWrite(TB2_IN2, HIGH);
  analogWrite(TB2_PWM, FRONT_SPEED);
}

void stopMotors() {
  analogWrite(BTS1_RPWM, 0);
  analogWrite(BTS1_LPWM, 0);
  analogWrite(BTS2_RPWM, 0);
  analogWrite(BTS2_LPWM, 0);
  analogWrite(TB1_PWM, 0);
  analogWrite(TB2_PWM, 0);
}
