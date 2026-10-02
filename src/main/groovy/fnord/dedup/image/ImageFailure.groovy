package fnord.dedup.image

class ImageFailure extends IOException {
    final String code
    final String category
    ImageFailure(String code, String message, String category = 'DEPENDENCY') {
        super(message); this.code = code; this.category = category
    }
}
