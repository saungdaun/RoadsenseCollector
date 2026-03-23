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

Dataset mencakup **15 class** dalam 2 kategori, mengacu pada terminologi baku penilaian kondisi jalan (PCI / IRI):

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

### 🟤 Jenis Permukaan (Surface)
| Code | Label |
|------|-------|
| P00 | Aspal |
| P01 | Beton |
| P02 | Paving Block |
| P03 | Kerikil |
| P04 | Tanah |

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
