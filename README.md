# 🛣️ RoadSense Collector

**Android app untuk koleksi dataset kondisi jalan raya** — dirancang khusus untuk membangun dataset pelatihan model YOLO (object detection & segmentation) yang mencakup kerusakan jalan, marka, rambu, dan fitur permukaan.

---

## 📱 Fitur Utama

### 📸 Kamera Collector
Ambil foto langsung dari kamera dengan pemilihan class secara real-time. Foto otomatis di-standarisasi ke ukuran **640×640 px** (format YOLO-ready) dan disimpan ke folder class masing-masing.

### 🎬 Video Frame Extractor
Ekstrak frame dari video secara otomatis dengan interval yang bisa diatur (1–11 detik). Dilengkapi dengan:
- **Deteksi blur otomatis** (Laplacian variance) — frame blur langsung ditandai ⚠️
- **Preview fullscreen** setiap frame sebelum di-assign — tap thumbnail untuk buka, pinch-to-zoom, double tap reset
- **Navigasi antar frame** langsung dari preview tanpa tutup dialog
- Long press thumbnail untuk toggle pilih langsung

### 🗂️ Gallery Browser
Import foto dari galeri perangkat, lalu assign ke class yang sesuai secara batch.

### 📚 Library
Lihat semua foto yang sudah terkumpul per class, lengkap dengan jumlah data.

### 📊 Statistik
Pantau progres pengumpulan data per class dan per kategori.

### 📤 Export YOLO
Export dataset ke format **YOLO Segmentation** siap pakai dengan split otomatis:
- Train : 80%
- Validation : 20%

Output disimpan ke `Pictures/RoadSenseCollector_Export/`.

---

## 🏷️ Label / Class

Dataset mencakup **36 class** dalam 5 kategori:

### 🔴 Kerusakan Jalan (Distress)
| Code | Label |
|------|-------|
| D00 | Retak Memanjang |
| D01 | Retak Melintang |
| D02 | Retak Buaya |
| D03 | Lubang (Pothole) |
| D04 | Alur (Rutting) |
| D05 | Pengelupasan |
| D06 | Retak Blok |
| D07 | Retak Tepi |
| D08 | Tambalan |
| D09 | Bleeding |

### 🔵 Marka Jalan (Marking)
| Code | Label |
|------|-------|
| M00 | Marka Lajur (Baik) |
| M01 | Marka Lajur (Pudar) |
| M02 | Zebra Cross (Baik) |
| M03 | Zebra Cross (Pudar) |
| M04 | Marka Panah |
| M05 | Garis Stop |

### 🟢 Rambu (Sign)
| Code | Label |
|------|-------|
| S00 | Rambu Kecepatan |
| S01 | Rambu Peringatan |
| S02 | Rambu Larangan |
| S03 | Rambu Petunjuk |
| S04 | Rambu Rusak |
| S05 | Rambu Hilang |

### ⚫ Fitur Jalan (Road Feature)
| Code | Label |
|------|-------|
| R00 | Penutup Manhole |
| R01 | Speed Bump |
| R02 | Tutup Selokan |
| R03 | Kerusakan Trotoar |
| R04 | Kerusakan Bahu Jalan |
| R05 | Bekas Galian |

### 🟤 Jenis Permukaan (Surface)
| Code | Label |
|------|-------|
| P00 | Aspal Baik |
| P01 | Aspal Rusak |
| P02 | Beton |
| P03 | Paving Block |
| P04 | Kerikil |
| P05 | Tanah |
| P06 | Tanah Berlubang |

---

## 🏗️ Tech Stack

- **Language:** Kotlin
- **Min SDK:** 26 (Android 8.0)
- **Target SDK:** 34
- **Architecture:** Single-module, Activity-based
- **Image standardization:** Custom `ImageStandardizer` → 640×640 px JPEG
- **Export format:** YOLO Segmentation (`images/` + `labels/`)
- **Storage:** MediaStore API (scoped storage compliant)

---

## 📂 Struktur Penyimpanan

```
Pictures/
└── RoadSenseCollector/
    ├── D00_Retak Memanjang/
    │   ├── D00_20250101_120000_001.jpg
    │   └── ...
    ├── D03_Lubang (Pothole)/
    └── ...

Pictures/
└── RoadSenseCollector_Export/
    ├── images/
    │   ├── train/
    │   └── val/
    └── labels/
        ├── train/
        └── val/
```

---

## 🚀 Build & Run

1. Clone repo ini
2. Buka di **Android Studio Hedgehog** atau lebih baru
3. Sync Gradle
4. Build & run ke device / emulator (API 26+)

> Pastikan izin **kamera** dan **storage** diberikan saat pertama kali dijalankan.

---

## 📋 Permissions

```xml
READ_MEDIA_VIDEO       <!-- Akses video (API 33+) -->
READ_EXTERNAL_STORAGE  <!-- Akses storage (API < 33) -->
CAMERA                 <!-- Foto langsung dari kamera -->
ACCESS_FINE_LOCATION   <!-- Tag GPS pada metadata foto -->
```

---

## 🤝 Kontribusi

Pull request dan issue sangat welcome — terutama untuk penambahan class baru, perbaikan deteksi blur, atau peningkatan akurasi label.
