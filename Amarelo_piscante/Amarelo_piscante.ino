#include <Adafruit_NeoPixel.h>

#define PIN 8 
#define NUMPIXELS 1

int state = 0;

Adafruit_NeoPixel pixels(NUMPIXELS, PIN, NEO_GRB + NEO_KHZ800);

void setup() {
  pixels.begin();
  pixels.setBrightness(64);
}

void loop() {
  pixels.clear();
  pixels.setPixelColor(0, pixels.Color(255, 255, 0));
  pixels.show();
  delay(1000);
  pixels.clear();
  pixels.setPixelColor(0, pixels.Color(0, 0, 0));
  pixels.show();
  delay(1000);
}
