const coverLimit = 2 * 1024 * 1024;

async function decodeCover(file: File) {
  if (typeof createImageBitmap === 'function') {
    try {
      const bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' });
      return { source: bitmap, width: bitmap.width, height: bitmap.height, close: () => bitmap.close() };
    } catch { /* Try the browser image decoder for formats unsupported by ImageBitmap. */ }
  }
  const url = URL.createObjectURL(file);
  const image = new Image();
  try {
    image.src = url;
    await image.decode();
    return { source: image, width: image.naturalWidth, height: image.naturalHeight, close: () => URL.revokeObjectURL(url) };
  } catch {
    URL.revokeObjectURL(url);
    throw new Error('This image couldn’t be opened. Choose a JPEG, PNG, or WebP image.');
  }
}

export default async function normalizeCover(file: File) {
  const image = await decodeCover(file);
  try {
    const canvas = document.createElement('canvas');
    const scale = Math.min(1, 1920 / image.width, 1440 / image.height);
    canvas.width = Math.max(1, Math.round(image.width * scale));
    canvas.height = Math.max(1, Math.round(image.height * scale));
    const context = canvas.getContext('2d', { alpha: false });
    if (!context) throw new Error('This browser couldn’t prepare the image.');
    context.fillStyle = '#fff';
    context.fillRect(0, 0, canvas.width, canvas.height);
    context.drawImage(image.source, 0, 0, canvas.width, canvas.height);
    for (const quality of [.86, .72, .56]) {
      const encoded = await new Promise<Blob | null>(resolve => canvas.toBlob(resolve, 'image/jpeg', quality));
      if (encoded?.type === 'image/jpeg' && encoded.size > 0 && encoded.size <= coverLimit) return encoded;
    }
    throw new Error('This image couldn’t be reduced to 2 MB. Choose a smaller image.');
  } finally { image.close(); }
}
