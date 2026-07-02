#!/usr/bin/env python3
########################################################################
# Filename    : calculadora.py
# Description : Calculator with 4x4 keypad and LCD1602
########################################################################
from time import sleep

from LCD1602 import CharLCD1602
import Keypad

ROWS = 4
COLS = 4
keys = [
    '1', '2', '3', 'A',
    '4', '5', '6', 'B',
    '7', '8', '9', 'C',
    '*', '0', '#', 'D',
]

rowsPins = [16, 20, 21, 26]
colsPins = [19, 13, 6, 5]

LCD_COLS = 16
OP_KEYS = {
    'A': '+',
    'B': '-',
    'C': '*',
    'D': '/',
}

lcd1602 = CharLCD1602()


class Calculator:
    def __init__(self):
        self.reset()

    def reset(self):
        self.left = ''
        self.right = ''
        self.operator = ''
        self.result = ''
        self.error = ''
        self.entering_result = False

    def handle_key(self, key):
        if key.isdigit():
            self._add_digit(key)
        elif key in OP_KEYS:
            self._set_operator(OP_KEYS[key])
        elif key == '#':
            self._calculate()
        elif key == '*':
            self.reset()

    def expression_text(self):
        if self.operator:
            right = self.right if self.right else '_'
            return f'{self.left} {self.operator} {right}'
        return self.left if self.left else '0'

    def status_text(self):
        if self.error:
            return self.error
        if self.result:
            return f'= {self.result}'
        if self.operator:
            return '#=  *=limpar'
        return 'A+ B- C* D/'

    def _add_digit(self, digit):
        if self.entering_result:
            self.reset()

        target = self.right if self.operator else self.left
        if target == '0':
            target = digit
        elif len(target) < 10:
            target += digit

        if self.operator:
            self.right = target
        else:
            self.left = target

        self.result = ''
        self.error = ''

    def _set_operator(self, operator):
        if self.error:
            self.reset()

        if self.result:
            self.left = self.result
            self.right = ''
            self.result = ''

        if not self.left:
            self.left = '0'

        if self.operator and self.right:
            self._calculate()
            if self.error:
                return
            self.left = self.result
            self.right = ''
            self.result = ''

        self.operator = operator
        self.entering_result = False

    def _calculate(self):
        if not self.operator or not self.left or not self.right:
            return

        left = self._parse_number(self.left)
        right = self._parse_number(self.right)

        try:
            if self.operator == '+':
                value = left + right
            elif self.operator == '-':
                value = left - right
            elif self.operator == '*':
                value = left * right
            else:
                if right == 0:
                    raise ZeroDivisionError
                value = left / right
        except ZeroDivisionError:
            self.error = 'Erro: div por 0'
            self.result = ''
            self.entering_result = True
            return

        self.result = self._format_number(value)
        self.error = ''
        self.entering_result = True

    def _format_number(self, value):
        if isinstance(value, float):
            if value.is_integer():
                return str(int(value))
            return f'{value:.6g}'
        return str(value)

    def _parse_number(self, text):
        if '.' in text:
            return float(text)
        return int(text)


def lcd_write_line(row, text):
    lcd1602.write(0, row, text[:LCD_COLS].ljust(LCD_COLS))


def render(calc):
    lcd_write_line(0, calc.expression_text())
    lcd_write_line(1, calc.status_text())


def loop():
    if not lcd1602.init_lcd():
        print('LCD init failed. Check I2C address and wiring.')
        return

    keypad = Keypad.Keypad(keys, rowsPins, colsPins, ROWS, COLS)
    keypad.setDebounceTime(50)

    calc = Calculator()
    render(calc)

    while True:
        key = keypad.getKey()
        if key != keypad.NULL:
            calc.handle_key(key)
            render(calc)
        sleep(0.02)


def destroy():
    lcd1602.clear()


if __name__ == '__main__':
    print('Program is starting ... ')
    try:
        loop()
    except KeyboardInterrupt:
        destroy()
