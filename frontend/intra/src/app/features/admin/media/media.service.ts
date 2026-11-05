import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';

@Injectable({ providedIn: 'root' })
export class MediaService {
  private readonly http = inject(HttpClient);

  listImages() {
    return this.http.get<string[]>('/api/intra/media/images');
  }

  uploadImages(files: File[]) {
    const form = new FormData();
    files.forEach(f => form.append('files', f));
    return this.http.post<{ urls: string[]; count: number }>('/api/intra/media/images', form);
  }

  deleteImage(filename: string) {
    return this.http.delete<void>(`/api/intra/media/images/${filename}`);
  }
}
