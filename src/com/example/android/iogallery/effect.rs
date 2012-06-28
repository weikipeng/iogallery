/**
 * Copyright (c) 2012, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#pragma version(1)
#pragma rs java_package_name(com.example.android.iogallery)
#pragma rs_fp_relaxed(relaxed)

const static float4 convertIntensity = { 0.299f, 0.587f, 0.114f, 0.f };

float4 darkColor;
float4 lightColor;
float strength;

void root(const uchar4 *inPixel, uchar4 *outPixel) {
    float4 inColor = convert_float4(*inPixel);

    // Mix between two color tones based on intensity
    float intensity = dot(convertIntensity, inColor) / 255.f;
    float4 tonedColor = mix(darkColor, lightColor, intensity);
    tonedColor = tonedColor * intensity;

    // Blend toned and original colors
    float4 outColor = mix(inColor, tonedColor, strength);
    *outPixel = convert_uchar4(outColor);
}
