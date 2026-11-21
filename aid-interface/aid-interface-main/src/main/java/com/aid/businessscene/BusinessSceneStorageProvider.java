package com.aid.businessscene;

import java.util.List;

/** A business module declares numeric admission policy; directories and credentials are never editable here. */
public interface BusinessSceneStorageProvider {
    boolean supports(String sceneCode);
    List<Field> fields(String sceneCode);
    record Field(String key,String name,String unit,long defaultValue,long minimum,long maximum) {
        public Field {
            if(key==null||!key.matches("[a-zA-Z0-9_.-]{1,100}")||name==null||name.isBlank()||unit==null
                    ||minimum<0||maximum<minimum||maximum>9007199254740991L||defaultValue<minimum||defaultValue>maximum)
                throw new IllegalArgumentException("工作区存储字段定义无效");
        }
        public long validate(long value) {
            if(value<minimum||value>maximum)throw new IllegalArgumentException(name+"超出允许范围");return value;
        }
    }
}
