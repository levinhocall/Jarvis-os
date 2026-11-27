#include "vector_index.h"

#include <usearch/index_dense.hpp>

#include <algorithm>
#include <cstring>

using namespace unum::usearch;

struct JarvisVectorIndex::Impl {
    std::size_t dimensions = 0;
    std::size_t connectivity = 16;
    std::size_t expansion_add = 64;
    std::size_t expansion_search = 32;
    mutable std::mutex mu;
    index_dense_t index;
    bool valid = false;
};

JarvisVectorIndex::JarvisVectorIndex(
    std::size_t dimensions,
    std::size_t connectivity,
    std::size_t expansion_add,
    std::size_t expansion_search)
    : impl_(std::make_unique<Impl>()) {
    impl_->dimensions = dimensions;
    impl_->connectivity = connectivity;
    impl_->expansion_add = expansion_add;
    impl_->expansion_search = expansion_search;
    if (dimensions == 0 || dimensions > 4096) return;

    metric_punned_t metric = metric_punned_t::builtin(
        dimensions, metric_kind_t::cos_k, scalar_kind_t::f32_k);
    index_dense_config_t config(connectivity, expansion_add, expansion_search);
    auto made = index_dense_t::make(metric, config, default_free_value<index_dense_t::vector_key_t>(), index_limits_t(128, 1));
    if (made) {
        impl_->index = std::move(made);
        impl_->valid = true;
    }
}

JarvisVectorIndex::~JarvisVectorIndex() = default;

bool JarvisVectorIndex::IsValid() const {
    return impl_ && impl_->valid;
}

std::size_t JarvisVectorIndex::Dimensions() const {
    std::lock_guard<std::mutex> lock(impl_->mu);
    return impl_->valid ? impl_->index.dimensions() : impl_->dimensions;
}

std::size_t JarvisVectorIndex::Size() const {
    std::lock_guard<std::mutex> lock(impl_->mu);
    return impl_->valid ? impl_->index.size() : 0;
}

bool JarvisVectorIndex::Add(std::int64_t key, const float* vector, std::size_t dimensions) {
    if (!vector) return false;
    std::lock_guard<std::mutex> lock(impl_->mu);
    if (!impl_->valid || dimensions != impl_->dimensions) return false;

    const auto ukey = static_cast<index_dense_t::vector_key_t>(key);
    if (impl_->index.contains(ukey)) {
        impl_->index.remove(ukey);
    }
    if (impl_->index.size() + 1 > impl_->index.capacity()) {
        std::size_t next_cap = std::max<std::size_t>(128, impl_->index.capacity() * 2);
        if (!impl_->index.try_reserve(index_limits_t(next_cap, 1))) {
            return false;
        }
    }
    auto res = impl_->index.add(ukey, vector);
    return static_cast<bool>(res);
}

bool JarvisVectorIndex::Remove(std::int64_t key) {
    std::lock_guard<std::mutex> lock(impl_->mu);
    if (!impl_->valid) return false;
    const auto ukey = static_cast<index_dense_t::vector_key_t>(key);
    if (!impl_->index.contains(ukey)) return false;
    auto res = impl_->index.remove(ukey);
    return static_cast<bool>(res);
}

std::size_t JarvisVectorIndex::Search(
    const float* query,
    std::size_t dimensions,
    std::size_t limit,
    std::int64_t* out_keys,
    float* out_distances) const {
    if (!query || !out_keys || !out_distances || limit == 0) return 0;
    std::lock_guard<std::mutex> lock(impl_->mu);
    if (!impl_->valid || dimensions != impl_->dimensions || impl_->index.size() == 0) return 0;

    std::size_t wanted = std::min(limit, impl_->index.size());
    auto res = impl_->index.search(query, wanted);
    if (!res) return 0;

    std::vector<index_dense_t::vector_key_t> tmp_keys(wanted);
    std::vector<float> tmp_dists(wanted);
    std::size_t found = res.dump_to(tmp_keys.data(), tmp_dists.data(), wanted);
    for (std::size_t i = 0; i < found; ++i) {
        out_keys[i] = static_cast<std::int64_t>(tmp_keys[i]);
        out_distances[i] = tmp_dists[i];
    }
    return found;
}

std::vector<std::uint8_t> JarvisVectorIndex::SaveBytes() const {
    std::lock_guard<std::mutex> lock(impl_->mu);
    std::vector<std::uint8_t> out;
    if (!impl_->valid) return out;
    out.reserve(impl_->index.serialized_length());
    auto res = impl_->index.save_to_stream([&out](const void* data, std::size_t bytes) -> bool {
        const auto* ptr = static_cast<const std::uint8_t*>(data);
        out.insert(out.end(), ptr, ptr + bytes);
        return true;
    });
    if (!res) out.clear();
    return out;
}

bool JarvisVectorIndex::LoadBytes(const std::uint8_t* data, std::size_t length) {
    if (!data || length == 0) return false;
    std::lock_guard<std::mutex> lock(impl_->mu);
    std::size_t offset = 0;
    auto res = impl_->index.load_from_stream([data, length, &offset](void* dst, std::size_t bytes) -> bool {
        if (offset + bytes > length) return false;
        std::memcpy(dst, data + offset, bytes);
        offset += bytes;
        return true;
    });
    if (res) {
        impl_->dimensions = impl_->index.dimensions();
        impl_->valid = true;
        return true;
    }
    return false;
}
