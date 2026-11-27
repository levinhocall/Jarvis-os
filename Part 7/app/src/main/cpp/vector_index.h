#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <vector>

namespace unum::usearch {
template <typename, typename> class index_dense_gt;
}

/**
 * Thread-safe C++ wrapper over USearch HNSW (Apache-2.0) for encrypted on-device
 * semantic memory retrieval (cosine metric, float32 embeddings).
 */
class JarvisVectorIndex {
public:
    explicit JarvisVectorIndex(
        std::size_t dimensions,
        std::size_t connectivity = 16,
        std::size_t expansion_add = 64,
        std::size_t expansion_search = 32);
    ~JarvisVectorIndex();

    JarvisVectorIndex(const JarvisVectorIndex&) = delete;
    JarvisVectorIndex& operator=(const JarvisVectorIndex&) = delete;

    bool IsValid() const;
    std::size_t Dimensions() const;
    std::size_t Size() const;

    bool Add(std::int64_t key, const float* vector, std::size_t dimensions);
    bool Remove(std::int64_t key);
    std::size_t Search(
        const float* query,
        std::size_t dimensions,
        std::size_t limit,
        std::int64_t* out_keys,
        float* out_distances) const;

    std::vector<std::uint8_t> SaveBytes() const;
    bool LoadBytes(const std::uint8_t* data, std::size_t length);

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};
