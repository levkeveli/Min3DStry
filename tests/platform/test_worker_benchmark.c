#include "platform/worker_benchmark.h"

#include <assert.h>
#include <stdio.h>

int main(void) {
    M3dsWorkerBenchmarkResult serial;
    M3dsWorkerBenchmarkResult two;
    M3dsWorkerBenchmarkResult four;

    assert(m3ds_worker_benchmark_run(1u, 2u, &serial));
    assert(serial.match);
    assert(serial.items == M3DS_WORKER_BENCH_ITEMS);
    assert(serial.chunks == 40u);
    assert(serial.workers == 1u);
    assert(serial.serial_checksum == serial.parallel_checksum);

    assert(m3ds_worker_benchmark_run(2u, 2u, &two));
    assert(two.match);
    assert(two.workers == 2u);
    assert(two.serial_checksum == two.parallel_checksum);
    assert(two.serial_checksum == serial.serial_checksum);

    assert(m3ds_worker_benchmark_run(4u, 2u, &four));
    assert(four.match);
    assert(four.workers == 4u);
    assert(four.serial_checksum == four.parallel_checksum);
    assert(four.serial_checksum == serial.serial_checksum);

    assert(!m3ds_worker_benchmark_run(2u, 0u, &two));
    assert(!m3ds_worker_benchmark_run(2u, 2u, NULL));

    printf(
        "test_worker_benchmark: all assertions passed (checksum=0x%08x).\n",
        serial.serial_checksum);
    return 0;
}
