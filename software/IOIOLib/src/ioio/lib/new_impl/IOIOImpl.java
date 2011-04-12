package ioio.lib.new_impl;

import ioio.lib.api.AnalogInput;
import ioio.lib.api.DigitalInput;
import ioio.lib.api.DigitalInputSpec;
import ioio.lib.api.DigitalInputSpec.Mode;
import ioio.lib.api.DigitalOutput;
import ioio.lib.api.DigitalOutputSpec;
import ioio.lib.api.IOIO;
import ioio.lib.api.PwmOutput;
import ioio.lib.api.Twi;
import ioio.lib.api.Twi.Rate;
import ioio.lib.api.Uart;
import ioio.lib.api.exception.ConnectionLostException;
import ioio.lib.api.exception.OutOfResourceException;
import ioio.lib.new_impl.IncomingState.DisconnectListener;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class IOIOImpl implements IOIO {
	IOIOProtocol protocol_;
	private boolean connected_ = true;
	private IncomingState incomingState_ = new IncomingState();
	private boolean openPins_[] = new boolean[Constants.NUM_PINS];
	private boolean openTwi_[] = new boolean[Constants.NUM_TWI_MODULES];
	private ModuleAllocator pwmAllocator_ = new ModuleAllocator(Constants.NUM_PWM_MODULES);
	private ModuleAllocator uartAllocator_ = new ModuleAllocator(Constants.NUM_UART_MODULES);
	
	public IOIOImpl(InputStream in, OutputStream out) throws InterruptedException, ConnectionLostException {
		protocol_ = new IOIOProtocol(in, out, incomingState_);
		incomingState_.waitConnect();
	}
	
	synchronized void removeDisconnectListener(DisconnectListener listener) {
		incomingState_.removeDisconnectListener(listener);
	}
	
	synchronized void addDisconnectListener(DisconnectListener listener) throws ConnectionLostException {
		incomingState_.addDisconnectListener(listener);
	}
	
	synchronized void closePin(int pin) {
		if (openPins_[pin]) {
			try {
				protocol_.setPinDigitalIn(pin, DigitalInputSpec.Mode.FLOATING);
			} catch (IOException e) {
			}
			openPins_[pin] = false;
		}
	}
	
	synchronized void closePwm(int pwmNum) {
		pwmAllocator_.releaseModule(pwmNum);
		try {
			protocol_.setPwmPeriod(pwmNum, 0, false);
		} catch (IOException e) {
		}
	}

	synchronized void closeUart(int uartNum) {
		uartAllocator_.releaseModule(uartNum);
		try {
			protocol_.uartConfigure(uartNum, 0, false, Uart.StopBits.ONE_STOP_BIT, Uart.Parity.NO_PARITY);
		} catch (IOException e) {
		}
	}
	
	synchronized void closeTwi(int twiNum) {
		if (!openTwi_[twiNum]) {
			throw new IllegalStateException("TWI not open: " + twiNum);
		}
		openTwi_[twiNum] = false;
		openPins_[Constants.TWI_PINS[twiNum][0]] = false;
		openPins_[Constants.TWI_PINS[twiNum][1]] = false;
		try {
			protocol_.i2cClose(twiNum);
		} catch (IOException e) {
		}
	}

	@Override
	synchronized public void disconnect() throws InterruptedException {
		protocol_.close();
		incomingState_.waitDisconnect();
	}

	@Override
	public boolean isConnected() {
		return connected_;
	}

	@Override
	synchronized public void softReset() throws ConnectionLostException {
		checkConnected();
		try {
			protocol_.softReset();
		} catch (IOException e) {
			throw new ConnectionLostException(e);
		}
	}

	@Override
	synchronized public void hardReset() throws ConnectionLostException {
		checkConnected();
		try {
			protocol_.hardReset();
		} catch (IOException e) {
			throw new ConnectionLostException(e);
		}
	}

	@Override
	public DigitalInput openDigitalInput(int pin)
			throws ConnectionLostException {
		return openDigitalInput(new DigitalInputSpec(pin));
	}

	@Override
	public DigitalInput openDigitalInput(int pin, Mode mode)
			throws ConnectionLostException {
		return openDigitalInput(new DigitalInputSpec(pin, mode));
	}

	@Override
	synchronized public DigitalInput openDigitalInput(DigitalInputSpec spec)
			throws ConnectionLostException {
		checkConnected();
		checkPinFree(spec.pin);
		DigitalInputImpl result = new DigitalInputImpl(this, spec.pin);
		openPins_[spec.pin] = true;
		incomingState_.addInputPinListener(spec.pin, result);
		try {
			protocol_.setPinDigitalIn(spec.pin, spec.mode);
			protocol_.setChangeNotify(spec.pin, true);
		} catch (IOException e) {
			throw new ConnectionLostException(e);
		}
		return result;
	}

	@Override
	public DigitalOutput openDigitalOutput(int pin,
			ioio.lib.api.DigitalOutputSpec.Mode mode, boolean startValue)
			throws ConnectionLostException {
		return openDigitalOutput(new DigitalOutputSpec(pin, mode), startValue);
	}

	@Override
	synchronized public DigitalOutput openDigitalOutput(DigitalOutputSpec spec,
			boolean startValue) throws ConnectionLostException {
		checkConnected();
		checkPinFree(spec.pin);
		DigitalOutputImpl result = new DigitalOutputImpl(this, spec.pin);
		openPins_[spec.pin] = true;
		try {
			protocol_.setPinDigitalOut(spec.pin, startValue, spec.mode);
		} catch (IOException e) {
			throw new ConnectionLostException(e);
		}
		return result;
	}

	@Override
	public DigitalOutput openDigitalOutput(int pin, boolean startValue)
			throws ConnectionLostException {
		return openDigitalOutput(new DigitalOutputSpec(pin), startValue);
	}

	@Override
	public DigitalOutput openDigitalOutput(int pin)
			throws ConnectionLostException {
		return openDigitalOutput(new DigitalOutputSpec(pin), false);
	}

	@Override
	synchronized public AnalogInput openAnalogInput(int pin) throws ConnectionLostException {
		checkConnected();
		checkPinFree(pin);
		AnalogInputImpl result = new AnalogInputImpl(this, pin);
		openPins_[pin] = true;
		incomingState_.addInputPinListener(pin, result);
		try {
			protocol_.setPinAnalogIn(pin);
		} catch (IOException e) {
			throw new ConnectionLostException(e);
		}
		return result;
	}

	@Override
	public PwmOutput openPwmOutput(int pin, int freqHz)
			throws ConnectionLostException {
		return openPwmOutput(new DigitalOutputSpec(pin), freqHz);
	}

	@Override
	synchronized public PwmOutput openPwmOutput(DigitalOutputSpec spec, int freqHz)
			throws ConnectionLostException {
		checkConnected();
		checkPinFree(spec.pin);
		Integer pwmNum = pwmAllocator_.allocateModule();
		if (pwmNum == null) {
			throw new OutOfResourceException("No more available PWM modules");
		}
		int period = 16000000 / freqHz - 1;
		boolean scale256 = false;
		int effectivePeriodMicroSec;
		if (period > 65535) {
			period = 16000000 / freqHz / 256 - 1;
			scale256 = true;
			effectivePeriodMicroSec = (period + 1) * 16;
		} else {
			effectivePeriodMicroSec = (period + 1) / 16;
		}
 		PwmImpl pwm = new PwmImpl(this, spec.pin, pwmNum, period, effectivePeriodMicroSec);
		openPins_[spec.pin] = true;
		try {
			protocol_.setPinDigitalOut(spec.pin, false, spec.mode);
			protocol_.setPinPwm(spec.pin, pwmNum);
			protocol_.setPwmPeriod(pwmNum, period, scale256);
		} catch (IOException e) {
			throw new ConnectionLostException(e);
		}
		return pwm;
	}

	@Override
	public Uart openUart(int rx, int tx, int baud, Uart.Parity parity, Uart.StopBits stopbits)
			throws ConnectionLostException {
		return openUart(rx == INVALID_PIN_NUMBER ? null : new DigitalInputSpec(rx),
						tx == INVALID_PIN_NUMBER ? null : new DigitalOutputSpec(tx),
						baud, parity, stopbits);
	}

	@Override
	synchronized public Uart openUart(DigitalInputSpec rx, DigitalOutputSpec tx, int baud,
			Uart.Parity parity, Uart.StopBits stopbits) throws ConnectionLostException {
		checkConnected();
		if (rx != null) {
			checkPinFree(rx.pin);
		}
		if (tx != null) {
			checkPinFree(tx.pin);
		}
		int rxPin = rx != null ? rx.pin : INVALID_PIN_NUMBER;
		int txPin = tx != null ? tx.pin : INVALID_PIN_NUMBER;
		Integer uartNum = uartAllocator_.allocateModule();
		if (uartNum == null) {
			throw new OutOfResourceException("No more available UART modules");
		}
 		UartImpl uart = new UartImpl(this, txPin, rxPin, uartNum);
 		incomingState_.addUartListener(uartNum, uart);
		try {
			if (rx != null) {
				openPins_[rx.pin] = true;
				protocol_.setPinDigitalIn(rx.pin, rx.mode);
				protocol_.setPinUartRx(rx.pin, uartNum, true);
			}
			if (tx != null) {
				openPins_[tx.pin] = true;
				protocol_.setPinDigitalOut(tx.pin, true, tx.mode);
				protocol_.setPinUartTx(tx.pin, uartNum, true);
			}
			boolean speed4x = true;
			int rate = Math.round(4000000.0f / baud) - 1;
			if (rate > 65535) {
				speed4x = false;
				rate = Math.round(1000000.0f / baud) - 1;
			}
			protocol_.uartConfigure(uartNum, rate, speed4x, stopbits, parity);
		} catch (IOException e) {
			throw new ConnectionLostException(e);
		}
		return uart;
	}

	@Override
	synchronized public Twi openTwi(int twiNum, Rate rate, boolean smbus) throws ConnectionLostException {
		checkConnected();
		checkTwiFree(twiNum);
		checkPinFree(Constants.TWI_PINS[twiNum][0]);
		checkPinFree(Constants.TWI_PINS[twiNum][1]);
		openPins_[Constants.TWI_PINS[twiNum][0]] = true;
		openPins_[Constants.TWI_PINS[twiNum][1]] = true;
		openTwi_[twiNum] = true;
		TwiImpl twi = new TwiImpl(this, twiNum);
		incomingState_.addTwiListener(twiNum, twi);
		try {
			protocol_.i2cConfigureMaster(twiNum, rate, smbus);
		} catch (IOException e) {
			throw new ConnectionLostException(e);
		}
		return twi;
	}

//	@Override
//	public IOIOSpi openSpi(int miso, int mosi, int clk, int select, int speed)
//			throws ConnectionLostException, InvalidOperationException {
//		// TODO Auto-generated method stub
//		return null;
//	}
//
//	@Override
//	synchronized public IOIOSpi openSpi(DigitalInputSpec miso, DigitalOutputSpec mosi,
//			DigitalOutputSpec clk, DigitalOutputSpec select, int speed)
//			throws ConnectionLostException, InvalidOperationException {
//		// TODO Auto-generated method stub
//		return null;
//	}
//
//	@Override
//	synchronized public IOIOTwi openTwi(int twiNum, int speed) throws ConnectionLostException,
//			InvalidOperationException {
//		// TODO Auto-generated method stub
//		return null;
//	}
	
	private void checkConnected() throws ConnectionLostException {
		if (!connected_) {
			throw new ConnectionLostException();
		}
	}
	
	private void checkPinFree(int pin) {
		if (openPins_[pin]) {
			throw new IllegalArgumentException("Pin already open: " + pin);
		}
	}
	
	private void checkTwiFree(int twi) {
		if (openTwi_[twi]) {
			throw new IllegalArgumentException("TWI already open: " + twi);
		}
	}
}