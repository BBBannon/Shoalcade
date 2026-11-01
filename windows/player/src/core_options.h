/*
 * ShoalcadePlayer: core options (SET_VARIABLES, SET_CORE_OPTIONS v1/v2/intl, GET_VARIABLE) (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#pragma once
#include <map>
#include <set>
#include <string>
#include <vector>

#include "libretro/libretro.h"
#include "nlohmann/json.hpp"

namespace sc {
class CoreOptions {
public:
    /** A value the player wants. Locked values (console safety rules) can't be replaced later. */
    void want(const std::string &key, const std::string &value, bool locked);
    /** A change from the shell. False (and nothing changes) if the option is locked. */
    bool change(const std::string &key, const std::string &value);

    void define_v0(const retro_variable *vars);
    void define_v1(const retro_core_option_definition *defs);
    void define_v2(const retro_core_options_v2 *opts);
    void set_visible(const char *key, bool visible);
    /** RETRO_ENVIRONMENT_SET_VARIABLE: the core changes its own option. */
    bool core_set(const char *key, const char *value);

    /** Value for GET_VARIABLE, or nullptr. Pointer stays valid until the option changes. */
    const char *get(const char *key);
    /** GET_VARIABLE_UPDATE: true once after any change. */
    bool take_updated();
    /** Every option the core declared, with its current value (for the `options` message and logs). */
    nlohmann::json describe() const;

private:
    struct Option {
        std::string desc;
        std::vector<std::string> values;
        std::string def, value;
        bool visible = true;
    };
    void define(const std::string &key, const std::string &desc, std::vector<std::string> values, std::string def);
    std::map<std::string, Option> opts_;
    std::vector<std::string> order_;
    std::map<std::string, std::string> wanted_;
    std::set<std::string> locked_;
    bool updated_ = false;
};
} // namespace sc
