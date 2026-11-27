#ifndef PITCHSCALE_SOUNDTOUCH_C_API_H
#define PITCHSCALE_SOUNDTOUCH_C_API_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef void* SoundTouchHandle;

SoundTouchHandle st_create(void);
void st_destroy(SoundTouchHandle handle);
void st_set_sample_rate(SoundTouchHandle handle, uint32_t sample_rate);
void st_set_channels(SoundTouchHandle handle, uint32_t num_channels);
void st_set_pitch_semitones(SoundTouchHandle handle, float semitones);
void st_put_samples_i16(SoundTouchHandle handle, const int16_t* samples, uint32_t num_samples);
uint32_t st_receive_samples_i16(SoundTouchHandle handle, int16_t* out_buffer, uint32_t max_samples);
void st_flush(SoundTouchHandle handle);
void st_clear(SoundTouchHandle handle);

#ifdef __cplusplus
}
#endif

#endif // PITCHSCALE_SOUNDTOUCH_C_API_H
