#include <Adafruit_NeoPixel.h>

#define PIN 8 
#define NUMPIXELS 1

int state = 0;

Adafruit_NeoPixel pixels(NUMPIXELS, PIN, NEO_GRB + NEO_KHZ800);

void setup() {
  pixels.begin();
}

void green_state(){
  pixels.clear();
  pixels.setPixelColor(0, pixels.Color(0, 255, 0));
  pixels.show();
  delay(3000);
}

void red_state(){
  pixels.clear();
  pixels.setPixelColor(0, pixels.Color(255, 0, 0));
  pixels.show();
  delay(4000);
}

void yellow_state(){
  pixels.clear();
  pixels.setPixelColor(0, pixels.Color(255, 255, 0));
  pixels.show();
  delay(1000);
}

void loop() {
  switch(state){
    case 1:
      green_state();
      state++;
      break;
    case 2:
      red_state();
      state++;
      break;
    case 3:
      yellow_state();
      state=1;
      break;
    default:
      state=1;
      break;
  }
}
