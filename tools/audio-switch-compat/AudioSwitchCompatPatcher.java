import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Adds the constructor used by Stream Video to LiveKit's AudioSwitch fork.
 *
 * Both SDKs rely on AudioSwitch 1.2.0 under the same Java package, but LiveKit's
 * maintained fork moved the shared implementation to AbstractAudioSwitch and
 * removed one binary-compatible convenience constructor. The inherited public
 * runtime API is otherwise the API Stream consumes. Keeping one implementation
 * avoids duplicate DEX classes while preserving native RTC in both adapters.
 */
public final class AudioSwitchCompatPatcher {
    private static final String TARGET = "com/twilio/audioswitch/AudioSwitch";
    private static final String COMPAT_DESCRIPTOR =
        "(Landroid/content/Context;"
            + "Lcom/twilio/audioswitch/bluetooth/BluetoothHeadsetConnectionListener;"
            + "ZLandroid/media/AudioManager$OnAudioFocusChangeListener;"
            + "Ljava/util/List;ILkotlin/jvm/internal/DefaultConstructorMarker;)V";
    private static final String LIVEKIT_DESCRIPTOR =
        "(Landroid/content/Context;ZLandroid/media/AudioManager$OnAudioFocusChangeListener;Ljava/util/List;)V";

    private AudioSwitchCompatPatcher() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected input and output class paths");
        }
        var input = Path.of(args[0]);
        var output = Path.of(args[1]);
        var reader = new ClassReader(Files.readAllBytes(input));
        var writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        var visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            private boolean compatibilityConstructorExists;

            @Override
            public MethodVisitor visitMethod(
                int access,
                String name,
                String descriptor,
                String signature,
                String[] exceptions
            ) {
                if (name.equals("<init>") && descriptor.equals(COMPAT_DESCRIPTOR)) {
                    compatibilityConstructorExists = true;
                }
                return super.visitMethod(access, name, descriptor, signature, exceptions);
            }

            @Override
            public void visitEnd() {
                if (!compatibilityConstructorExists) {
                    var method = super.visitMethod(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
                        "<init>",
                        COMPAT_DESCRIPTOR,
                        null,
                        null
                    );
                    method.visitCode();
                    method.visitVarInsn(Opcodes.ALOAD, 0);
                    method.visitVarInsn(Opcodes.ALOAD, 1);
                    // Stream supplies false as AudioSwitch's default logging value.
                    method.visitVarInsn(Opcodes.ILOAD, 3);
                    method.visitVarInsn(Opcodes.ALOAD, 4);
                    method.visitVarInsn(Opcodes.ALOAD, 5);
                    method.visitMethodInsn(
                        Opcodes.INVOKESPECIAL,
                        TARGET,
                        "<init>",
                        LIVEKIT_DESCRIPTOR,
                        false
                    );
                    method.visitInsn(Opcodes.RETURN);
                    method.visitMaxs(0, 0);
                    method.visitEnd();
                }
                super.visitEnd();
            }
        };
        reader.accept(visitor, 0);
        Files.createDirectories(output.getParent());
        Files.write(output, writer.toByteArray());
    }
}
