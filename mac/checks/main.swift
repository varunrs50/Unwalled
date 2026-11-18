// Self-check for the rules that keep names from the phone inside their folders.
// Run: mac/scripts/check.sh
let bad = ["", ".", "..", "../x", "a/b", "/etc/passwd", "x\0y"]
for name in bad { assert(!SafeName.isPlainFile(name), "accepted file name \(name.debugDescription)") }
for name in ["photo.jpg", "Scan 2.pdf", "..hidden", "a..b"] { assert(SafeName.isPlainFile(name), "rejected \(name)") }

for name in [".", "..", "../../Desktop/x", "a/b", "com.app/../x", ""] { assert(!SafeName.isPackage(name), "accepted package \(name.debugDescription)") }
for name in ["com.whatsapp", "app.unwalled.debug", "com.google.android.apps.nbu.paisa.user"] { assert(SafeName.isPackage(name), "rejected \(name)") }
print("Name checks passed")
