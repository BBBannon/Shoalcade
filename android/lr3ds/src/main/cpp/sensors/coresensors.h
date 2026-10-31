#ifndef LIBRETRODROID_CORESENSORS_H
#define LIBRETRODROID_CORESENSORS_H

#include <array>
#include <atomic>
#include <mutex>

#include "libretro.h"

namespace libretrodroid {

/**
 * The libretro sensor interface served to cores on port 0.
 *
 * Readings use the libretro.h units: accelerometer in g including gravity,
 * gyroscope in rad/s, illuminance in lux. A sensor answers ENABLE only while
 * its bit is in the access mask, and reads return 0 for any sensor that is not
 * both enabled by the core and currently allowed.
 */
class CoreSensors {
public:
    static constexpr int ACCELEROMETER = 1;
    static constexpr int GYROSCOPE = 2;
    static constexpr int ILLUMINANCE = 4;

    static CoreSensors& getInstance();

    static retro_sensor_interface getInterface();

    void setAccess(int mask);
    void setAccelerometerScale(float scale);
    void setReadings(const std::array<float, 7>& readings);
    bool takeRequestChange(int& requested);
    void reset();

    bool setSensorState(unsigned port, retro_sensor_action action);
    float getSensorInput(unsigned port, unsigned id);

private:
    static bool callbackSetSensorState(unsigned port, retro_sensor_action action, unsigned rate);
    static float callbackGetSensorInput(unsigned port, unsigned id);

    static int sensorForAction(retro_sensor_action action);
    static bool isEnableAction(retro_sensor_action action);

    std::atomic<int> access {0};
    std::atomic<float> accelerometerScale {1.0f};
    std::atomic<int> requested {0};
    std::atomic<bool> requestChanged {false};
    std::mutex readingsMutex;
    std::array<float, 7> readings {};
};

}

#endif
