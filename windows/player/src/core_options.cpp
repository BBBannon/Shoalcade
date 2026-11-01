/*
 * ShoalcadePlayer: core options (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "core_options.h"

#include <algorithm>
#include <cstring>

#include "log.h"

namespace sc {
void CoreOptions::want(const std::string &key, const std::string &value, bool locked) {
    if (locked_.count(key) && wanted_[key] != value) {
        LOGW("option %s is locked to %s; %s ignored", key.c_str(), wanted_[key].c_str(), value.c_str());
        return;
    }
    wanted_[key] = value;
    if (locked) locked_.insert(key);
    auto it = opts_.find(key);
    if (it != opts_.end() && it->second.value != value) {
        it->second.value = value;
        updated_ = true;
    }
}

bool CoreOptions::change(const std::string &key, const std::string &value) {
    if (locked_.count(key)) return false;
    want(key, value, false);
    auto it = opts_.find(key);
    if (it != opts_.end() && !it->second.values.empty() &&
        std::find(it->second.values.begin(), it->second.values.end(), value) == it->second.values.end())
        LOGW("option %s: \"%s\" isn't one of the core's values; the core may fall back to its default",
             key.c_str(), value.c_str());
    return true;
}

void CoreOptions::define(const std::string &key, const std::string &desc, std::vector<std::string> values,
                         std::string def) {
    bool is_new = !opts_.count(key);
    if (is_new) order_.push_back(key);
    Option &o = opts_[key];
    std::string before = o.value;
    o.desc = desc;
    o.values = std::move(values);
    o.def = def.empty() && !o.values.empty() ? o.values.front() : def;
    auto w = wanted_.find(key);
    if (w != wanted_.end()) {
        o.value = w->second;
        if (!o.values.empty() && std::find(o.values.begin(), o.values.end(), o.value) == o.values.end())
            LOGW("option %s: wanted \"%s\" isn't one of the core's values (%zu listed); the core may fall back to "
                 "its default", key.c_str(), o.value.c_str(), o.values.size());
    } else if (is_new) {
        o.value = o.def;
    }
    // Cores re-send their option list (e.g. to change what is shown); that isn't a change of value.
    if (is_new || o.value != before) updated_ = true;
}

void CoreOptions::define_v0(const retro_variable *vars) {
    for (; vars && vars->key; vars++) {
        // "Description; value1|value2|value3", first value is the default.
        std::string v = vars->value ? vars->value : "";
        std::string desc, list;
        size_t semi = v.find("; ");
        if (semi != std::string::npos) desc = v.substr(0, semi), list = v.substr(semi + 2);
        else list = v;
        std::vector<std::string> values;
        size_t start = 0;
        while (start <= list.size()) {
            size_t bar = list.find('|', start);
            values.push_back(list.substr(start, bar == std::string::npos ? std::string::npos : bar - start));
            if (bar == std::string::npos) break;
            start = bar + 1;
        }
        define(vars->key, desc, values, values.empty() ? "" : values.front());
    }
}

void CoreOptions::define_v1(const retro_core_option_definition *defs) {
    for (; defs && defs->key; defs++) {
        std::vector<std::string> values;
        for (int i = 0; i < RETRO_NUM_CORE_OPTION_VALUES_MAX && defs->values[i].value; i++)
            values.push_back(defs->values[i].value);
        define(defs->key, defs->desc ? defs->desc : "", values, defs->default_value ? defs->default_value : "");
    }
}

void CoreOptions::define_v2(const retro_core_options_v2 *opts) {
    if (!opts) return;
    for (auto *d = opts->definitions; d && d->key; d++) {
        std::vector<std::string> values;
        for (int i = 0; i < RETRO_NUM_CORE_OPTION_VALUES_MAX && d->values[i].value; i++)
            values.push_back(d->values[i].value);
        define(d->key, d->desc ? d->desc : "", values, d->default_value ? d->default_value : "");
    }
}

void CoreOptions::set_visible(const char *key, bool visible) {
    if (!key) return;
    auto it = opts_.find(key);
    if (it != opts_.end()) it->second.visible = visible;
}

bool CoreOptions::core_set(const char *key, const char *value) {
    if (!key || !value) return false;
    auto it = opts_.find(key);
    if (it == opts_.end()) return false;
    if (locked_.count(key)) {
        LOGW("core tried to change locked option %s to %s; kept %s", key, value, it->second.value.c_str());
        return false;
    }
    if (it->second.value != value) {
        it->second.value = value;
        updated_ = true;
    }
    return true;
}

const char *CoreOptions::get(const char *key) {
    if (!key) return nullptr;
    auto it = opts_.find(key);
    if (it != opts_.end()) return it->second.value.c_str();
    // Some cores read options they never declared; give them what we want if we have it.
    auto w = wanted_.find(key);
    if (w != wanted_.end()) return w->second.c_str();
    return nullptr;
}

bool CoreOptions::take_updated() {
    bool u = updated_;
    updated_ = false;
    return u;
}

nlohmann::json CoreOptions::describe() const {
    nlohmann::json out = nlohmann::json::array();
    for (const auto &k : order_) {
        const Option &o = opts_.at(k);
        out.push_back({{"key", k}, {"value", o.value}, {"default", o.def}, {"values", o.values},
                       {"locked", locked_.count(k) > 0}, {"visible", o.visible}});
    }
    return out;
}
} // namespace sc
