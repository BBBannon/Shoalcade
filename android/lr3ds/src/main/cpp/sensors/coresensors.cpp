#include "coresensors.h"

namespace libretrodroid {

CoreSensors& CoreSensors::getInstance() {
    static CoreSensors instance;
    return instance;
}

retro_sensor_interface CoreSensors::getInterface() {
    return retro_sensor_interface {
        &CoreSensors::callbackSetSensorState,
        &CoreSensors::callbackGetSensorInput
    };
}

bool CoreSensors::callbackSetSensorState(unsigned port, retro_sensor_action action, unsigned) {
    return getInstance().setSensorState(port, action);
}

float CoreSensors::callbackGetSensorInput(unsigned port, unsigned id) {
    return getInstance().getSensorInput(port, id);
}

int CoreSensors::sensorForAction(retro_sensor_action action) {
    switch (action) {
        case RETRO_SENSOR_ACCELEROMETER_ENABLE:
        case RETRO_SENSOR_ACCELEROMETER_DISABLE:
            return ACCELEROMETER;
        case RETRO_SENSOR_GYROSCOPE_ENABLE:
        case RETRO_SENSOR_GYROSCOPE_DISABLE:
            return GYROSCOPE;
        case RETRO_SENSOR_ILLUMINANCE_ENABLE:
        case RETRO_SENSOR_ILLUMINANCE_DISABLE:
            return ILLUMINANCE;
        default:
            return 0;
    }
}

bool CoreSensors::isEnableAction(retro_sensor_action action) {
    return action == RETRO_SENSOR_ACCELEROMETER_ENABLE
        || action == RETRO_SENSOR_GYROSCOPE_ENABLE
        || action == RETRO_SENSOR_ILLUMINANCE_ENABLE;
}

void CoreSensors::setAccess(int mask) {
    access = mask;
}

void CoreSensors::setAccelerometerScale(float scale) {
    accelerometerScale = scale;
}

void CoreSensors::setReadings(const std::array<float, 7>& values) {
    std::lock_guard<std::mutex> lock(readingsMutex);
    readings = values;
}

bool CoreSensors::takeRequestChange(int& current) {
    if (!requestChanged.exchange(false)) return false;
    current = requested;
    return true;
}

void CoreSensors::reset() {
    requested = 0;
    requestChanged = true;
    std::lock_guard<std::mutex> lock(readingsMutex);
    readings = {};
}

bool CoreSensors::setSensorState(unsigned port, retro_sensor_action action) {
    int sensor = sensorForAction(action);
    if (port != 0 || sensor == 0) return false;

    if (isEnableAction(action)) {
        if ((access & sensor) == 0) return false;
        requested |= sensor;
    } else {
        requested &= ~sensor;
    }
    requestChanged = true;
    return true;
}

float CoreSensors::getSensorInput(unsigned port, unsigned id) {
    if (port != 0 || id > RETRO_SENSOR_ILLUMINANCE) return 0.0f;

    int sensor = id <= RETRO_SENSOR_ACCELEROMETER_Z ? ACCELEROMETER
        : id <= RETRO_SENSOR_GYROSCOPE_Z ? GYROSCOPE
        : ILLUMINANCE;
    if ((requested & access & sensor) == 0) return 0.0f;

    std::lock_guard<std::mutex> lock(readingsMutex);
    return sensor == ACCELEROMETER ? readings[id] * accelerometerScale : readings[id];
}

}
