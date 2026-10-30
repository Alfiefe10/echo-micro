cmake_minimum_required(VERSION 3.22.1)
project(echornnoise C)

set(RNNOISE ${CMAKE_CURRENT_SOURCE_DIR}/rnnoise)

add_library(echornnoise SHARED
  rnnoise_jni.c
  ${RNNOISE}/src/denoise.c
  ${RNNOISE}/src/rnn.c
  ${RNNOISE}/src/pitch.c
  ${RNNOISE}/src/kiss_fft.c
  ${RNNOISE}/src/celt_lpc.c
  ${RNNOISE}/src/nnet.c
  ${RNNOISE}/src/nnet_default.c
  ${RNNOISE}/src/parse_lpcnet_weights.c
  ${RNNOISE}/src/rnnoise_data.c
  ${RNNOISE}/src/rnnoise_tables.c)

target_include_directories(echornnoise PRIVATE ${RNNOISE}/include ${RNNOISE}/src)
target_compile_options(echornnoise PRIVATE -O3 -w)
target_link_libraries(echornnoise m)
