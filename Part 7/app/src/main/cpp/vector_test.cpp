#include "vector_index.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <random>
#include <set>
#include <vector>

static std::vector<float> Normalize(std::vector<float> v) {
    double sum = 0.0;
    for (float x : v) sum += static_cast<double>(x) * x;
    float norm = static_cast<float>(std::sqrt(sum));
    if (norm > 0.f) {
        for (float& x : v) x /= norm;
    }
    return v;
}

int main() {
    constexpr std::size_t kDim = 384;
    constexpr std::size_t kCount = 500;
    constexpr std::size_t kTopK = 5;

    JarvisVectorIndex idx(kDim, 16, 64, 32);
    if (!idx.IsValid()) {
        std::fprintf(stderr, "Failed to initialize USearch index\n");
        return 1;
    }

    std::mt19937 rng(42);
    std::normal_distribution<float> dist(0.f, 1.f);
    std::vector<std::vector<float>> vectors(kCount, std::vector<float>(kDim));

    for (std::size_t i = 0; i < kCount; ++i) {
        for (std::size_t d = 0; d < kDim; ++d) vectors[i][d] = dist(rng);
        vectors[i] = Normalize(std::move(vectors[i]));
        if (!idx.Add(static_cast<std::int64_t>(i + 1), vectors[i].data(), kDim)) {
            std::fprintf(stderr, "Failed adding key %zu\n", i + 1);
            return 2;
        }
    }
    if (idx.Size() != kCount) {
        std::fprintf(stderr, "Unexpected index size %zu\n", idx.Size());
        return 3;
    }

    // Measure recall@5 against exact brute-force cosine similarity across 20 queries.
    int hits = 0;
    constexpr int kQueries = 20;
    for (int q = 0; q < kQueries; ++q) {
        std::vector<float> qvec(kDim);
        for (std::size_t d = 0; d < kDim; ++d) qvec[d] = dist(rng);
        qvec = Normalize(std::move(qvec));

        std::vector<std::pair<float, std::int64_t>> exact;
        exact.reserve(kCount);
        for (std::size_t i = 0; i < kCount; ++i) {
            float dot = 0.f;
            for (std::size_t d = 0; d < kDim; ++d) dot += qvec[d] * vectors[i][d];
            exact.emplace_back(1.0f - dot, static_cast<std::int64_t>(i + 1));
        }
        std::sort(exact.begin(), exact.end());
        std::set<std::int64_t> expected;
        for (std::size_t j = 0; j < kTopK; ++j) expected.insert(exact[j].second);

        std::int64_t out_keys[kTopK]{};
        float out_dists[kTopK]{};
        std::size_t found = idx.Search(qvec.data(), kDim, kTopK, out_keys, out_dists);
        if (found != kTopK) return 4;
        for (std::size_t j = 0; j < found; ++j) {
            if (expected.count(out_keys[j])) hits++;
        }
    }
    double recall = static_cast<double>(hits) / static_cast<double>(kQueries * kTopK);
    std::printf("USearch recall@5=%.4f size=%zu\n", recall, idx.Size());
    if (recall < 0.90) {
        std::fprintf(stderr, "Recall below threshold: %.4f\n", recall);
        return 5;
    }

    // Test binary save/load roundtrip + remove()
    const auto snapshot = idx.SaveBytes();
    if (snapshot.empty()) return 6;

    JarvisVectorIndex restored(kDim);
    if (!restored.LoadBytes(snapshot.data(), snapshot.size())) return 7;
    if (restored.Size() != kCount) return 8;

    std::int64_t top1_key[1]{};
    float top1_dist[1]{};
    restored.Search(vectors[10].data(), kDim, 1, top1_key, top1_dist);
    if (top1_key[0] != 11) {
        std::fprintf(stderr, "Expected exact self-hit 11 after reload, got %lld\n",
                     static_cast<long long>(top1_key[0]));
        return 9;
    }
    if (!restored.Remove(11) || restored.Size() != kCount - 1) return 10;

    std::printf("USearch vector_test PASSED (snapshot=%zu bytes)\n", snapshot.size());
    return 0;
}
