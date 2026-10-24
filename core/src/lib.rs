use jni::{EnvUnowned, objects::{JClass, JString}, sys::JNIEnv};

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_eol_app_RustCore_helloworld<'caller>(
    mut unowned_env: EnvUnowned<'caller>,
    class: JClass<'caller>,
    input: JString<'caller>
) -> JString<'caller>
{

    let outcome = unowned_env.with_env(|env| -> Result<_, jni::errors::Error>{
        let input: String = input.to_string();

        JString::from_str(env, format!("Hellow from rust {}", input))
    });

    outcome.resolve::<jni::errors::ThrowRuntimeExAndDefault>()
}